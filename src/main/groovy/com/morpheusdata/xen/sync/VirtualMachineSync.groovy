package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.core.util.SyncUtils
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ResourcePermission
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.VM
import groovy.util.logging.Slf4j

@Slf4j
class VirtualMachineSync {

    private Cloud cloud
    private XenserverPlugin plugin
    private MorpheusContext morpheusContext
    private CloudProvider cloudProvider

    VirtualMachineSync(Cloud cloud, XenserverPlugin plugin, CloudProvider cloudProvider) {
        this.cloud = cloud
        this.plugin = plugin
        this.morpheusContext = plugin.morpheusContext
        this.@cloudProvider = cloudProvider
    }

    def execute() {
        try{
            def usageLists = [restartUsageIds: [], stopUsageIds: [], startUsageIds: [], updatedSnapshotIds: []]
            def authConfig = plugin.getAuthConfig(cloud)
            def listResults = XenComputeUtility.listVirtualMachines(authConfig)
            if (listResults.success == true){
                def domainRecords = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null)
                def blackListedNames = domainRecords.filter { it.status == 'provisioning' }
                        .map { it.name }.toList().blockingGet()

                SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, listResults.vmList as Collection<Map>)
                syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
                    domainObject.externalId == cloudItem.vm.uuid
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
                    morpheusContext.async.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
                        SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map> matchItem = updateItemMap[server.id]
                        return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: matchItem.masterItem)
                    }
                }.onAdd {itemsToAdd ->
                    addMissingVirtualMachines(itemsToAdd, blackListedNames, usageLists)
                }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, VM.Record>> updateItems ->
                    updateMatchedVirtualMachines(updateItems)
                }.onDelete { removeItems ->
                    removeMissingVirtualMachines(removeItems)
                }.observe().blockingSubscribe()
            }
        } catch (Exception ex) {
            log.error("VirtualMachineSync error: {}", ex.getMessage())
        }
    }

    def addMissingVirtualMachines(List addList, List blackListedNames,Map usageLists) {
        log.debug("addMissingVirtualMachines ${cloud} ${addList?.size()} ${blackListedNames} ${usageLists}")
        ServicePlan servicePlan = morpheusContext.services.servicePlan.find(new DataQuery().withFilter("code","internal-custom-xen"))

        Collection<ServicePlan> availablePlans =  morpheusContext.services.servicePlan.list(new DataQuery().withFilters(
                new DataFilter("active", true),
                new DataFilter("deleted", "ne", true),
                new DataFilter("provisionType.code", "xen")
        ))

        Collection<ResourcePermission> availablePlanPermissions = []
        if(availablePlans) {
            availablePlanPermissions = morpheusContext.services.resourcePermission.list(new DataQuery().withFilters(
                    new DataFilter("morpheusResourceType", "ServicePlan"),
                    new DataFilter("morpheusResourceId", "in", availablePlans.collect{pl -> pl.id})
            ))
        }

        for (cloudItem in addList) {
            try {
                    def vmConfig = buildVmConfig(cloudItem, servicePlan, availablePlans, availablePlanPermissions)
                    ComputeServer add = new ComputeServer(vmConfig)
                    if (servicePlan) {
                        applyServicePlan(add, servicePlan)
                    }
                    ComputeServer savedServer = morpheusContext.async.computeServer.create(add).blockingGet()
                    if (!savedServer) {
                        log.error "Error in creating server ${add}"
                    } else {
                        performPostSaveSync(savedServer)
                    }
                    if (vmConfig.powerState == ComputeServer.PowerState.on) {
                        usageLists.startUsageIds << savedServer.id
                    } else {
                        usageLists.stopUsageIds << savedServer.id
                    }
            } catch (Exception ex) {
                log.error("Error in adding VM: {}", ex)
            }
        }
    }

    private buildVmConfig(Map cloudItem, ServicePlan servicePlan, Collection<ServicePlan> availablePlans, Collection<ResourcePermission> availablePlanPermissions) {
        def computeServerType = cloudProvider.computeServerTypes.find {
            it.code == 'xenserverUnmanaged'
        }

        def vmConfig = [
                account          : cloud.account,
                externalId       : cloudItem.vm.uuid,
                name             : cloudItem.vm.nameLabel,
                sshUsername      : 'root',
                provision        : false,
                singleTenant     : true,
                cloud            : cloud,
                lvmEnabled       : false,
                managed          : false,
                discovered       : true,
                serverType       : 'unmanaged',
                status           : 'provisioned',
                uniqueId         : cloudItem.vm.uuid,
                powerState       : cloudItem.powerState == 'Running' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
                maxMemory        : cloudItem.vm.memoryTarget,
                maxCores         : cloudItem.vm.VCPUsMax,
                apiKey           : java.util.UUID.randomUUID(),
                maxStorage       : cloudItem.totalDiskSize?.toLong() ?: 0,
                computeServerType: computeServerType,
                category         : "xen.vm.${cloud.id}",
                plan:  SyncUtils.findServicePlanBySizing(availablePlans, cloudItem.vm.memoryTarget, cloudItem.vm.VCPUsMax, null, servicePlan, null, cloud.account, availablePlanPermissions)
        ]

        def vmNetworks = cloudItem.guestMetrics ? cloudItem.guestMetrics['networks'] : [:]
        def vmIp = vmNetworks['0/ip']
        if(vmIp) {
            vmConfig << [
                    externalIp : vmIp,
                    sshHost : vmIp,
                    internalIp : vmIp
            ]
        }
        vmConfig
    }

    private applyServicePlan(ComputeServer server, ServicePlan servicePlan) {
        server.plan = servicePlan
        server.maxCores = servicePlan.maxCores
        server.maxCpu = servicePlan.maxCpu
        server.maxMemory = servicePlan.maxMemory
        if (server.computeCapacityInfo) {
            server.computeCapacityInfo.maxCores = server.maxCores
            server.computeCapacityInfo.maxCpu = server.maxCpu
            server.computeCapacityInfo.maxMemory = server.maxMemory
        }
    }

    private Boolean performPostSaveSync(ComputeServer server) {
        log.debug("performPostSaveSync: ${server?.id}")
        def changes = false
        // Disks and metrics
        if (server.status != 'resizing') {
            if (!server.computeCapacityInfo) {
                server.capacityInfo = new ComputeCapacityInfo(maxCores: server.maxCores, maxMemory: server.maxMemory, maxStorage: server.maxStorage)
                changes = true
            }
        }
        if (changes) {
            saveAndGet(server)
        }
        return changes
    }

    protected ComputeServer saveAndGet(ComputeServer server) {
        def saveSuccessful = morpheusContext.async.computeServer.save([server]).blockingGet()
        if (!saveSuccessful) {
            log.warn("Error saving server: ${server?.id}")
        }
        return morpheusContext.async.computeServer.get(server.id).blockingGet()
    }

    void updateMatchedVirtualMachines(List<SyncTask.UpdateItem<ComputeServer, Map>> updateList) {
        log.debug("VirtualMachineSync >> updateMatchedVirtualMachines() called")
        List<ComputeServer> saves = []
        for (update in updateList) {
            ComputeServer currentServer = update.existingItem
            Map cloudItem = update.masterItem
            if (currentServer.status != 'provisioning') {
                try {
                    def	maxCpu = cloudItem.vm.VCPUsMax?.toLong() ?: 1
                    def maxMemory = cloudItem.vm.memoryTarget?.toLong() ?: 0
                    def maxStorage = cloudItem.totalDiskSize?.toLong() ?: 0
                    def save = false
                    def capacityInfo = currentServer.capacityInfo ?: new ComputeCapacityInfo(maxMemory:maxMemory, maxStorage:maxStorage)
                    if(maxMemory > capacityInfo.maxMemory) {
                        capacityInfo.maxMemory = maxMemory
                        save = true
                    }
                    if(maxCpu && capacityInfo.maxCores != maxCpu && maxCpu > 0) {
                        currentServer.maxCores = maxCpu
                        capacityInfo.maxCores = maxCpu
                        save = true
                    }
                    def maxUsedStorage = 0L
                    if(currentServer.agentInstalled) {
                        maxUsedStorage = currentServer.usedStorage
                    }
                    currentServer.capacityInfo = capacityInfo
                    if (save) {
                        saves << currentServer
                    }
                } catch (e) {
                    log.error("Error Updating Virtual Machine - ${e}", e)
                }
            }
        }
        if (saves) {
            morpheusContext.async.computeServer.bulkSave(saves).blockingGet()
        }
    }

    def removeMissingVirtualMachines(List<ComputeServerIdentityProjection> removeList) {
        log.debug("removeMissingVirtualMachines: ${cloud} ${removeList.size()}")
        morpheusContext.async.computeServer.remove(removeList).blockingGet()
    }
}
