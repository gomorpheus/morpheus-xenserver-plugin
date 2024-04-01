package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.ServicePlanIdentityProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.VM
import com.xensource.xenapi.VMGuestMetrics
import groovy.util.logging.Slf4j

@Slf4j
class VirtualMachineSync {

    private Cloud cloud
    private XenserverPlugin plugin
    private MorpheusContext morpheusContext
    private Map<String, ServicePlanIdentityProjection> servicePlans
    private CloudProvider cloudProvider

    VirtualMachineSync(Cloud cloud, XenserverPlugin plugin, CloudProvider cloudProvider) {
        this.cloud = cloud
        this.plugin = plugin
        this.morpheusContext = plugin.morpheusContext
        this.@cloudProvider = cloudProvider
    }

    def execute() {
        try{
//            def statsData = []
//            def rtn = [stopUsageIds:[], startUsageIds: [], restartUsageIds: []]
            def usageLists = [restartUsageIds: [], stopUsageIds: [], startUsageIds: [], updatedSnapshotIds: []]
            def listResults = XenComputeUtility.listVirtualMachines(cloud, plugin)
            log.info("RAZI :: listResults: ${listResults}")
            log.info("RAZI :: listResults.vmList: ${listResults.vmList}")
//            ServicePlan fallbackPlan = new ServicePlan(code: 'internal-custom-xen')
//            Collection<ServicePlan> availablePlans = ServicePlan.where{active == true && deleted != true && provisionType.code == 'xen'}.list()
//            Collection<ResourcePermission> availablePlanPermissions = []
//            if(availablePlans) {
//                availablePlanPermissions = ResourcePermission.where{ morpheusResourceType == 'ServicePlan' && morpheusResourceId in availablePlans.collect{pl -> pl.id}}.list()
//            }
            ServicePlan fallbackPlan = morpheusContext.services.servicePlan.find(new DataQuery().withFilter("code","internal-custom-xen"))
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

            if (listResults.success == true){
                def domainRecords = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null)
                def blackListedNames = domainRecords.filter { it.status == 'provisioning' }
                        .map { it.name }.toList().blockingGet()

                SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, listResults.vmList as Collection<Map>)
                syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
//                    domainObject.externalId == cloudItem.id.toString()
                    domainObject.externalId == cloudItem.vm.uuid
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
                    morpheusContext.async.computeServer.listById(updateItems?.collect { it.existingItem.id }).map { ComputeServer server ->
                        SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map> matchItem = updateItemMap[server.id]
                        return new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: matchItem.masterItem)
                    }
                }.onAdd {itemsToAdd ->
                    log.info("RAZI :: itemsToAdd: ${itemsToAdd}")
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
        for (cloudItem in addList) {
            try {
                def servicePlan = cloudItem ? xenServerServicePlans["xen.size.${cloudItem.size_slug}".toString()] : null
//                def zonePool = allZonePools[cloudItem.vpc_uuid]
//                def doCreate = zonePool?.inventory != false && !blackListedNames?.contains(cloudItem.name)
//                if (doCreate) {
                    log.info("RAZI :: servicePlan: ${servicePlan}")
                    log.info("RAZI :: cloudItem: ${cloudItem}")
                    def vmConfig = buildVmConfig(cloudItem)
                    log.info("RAZI :: vmConfig: ${vmConfig}")
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
//                }
            } catch (Exception ex) {
                log.error("Error in adding VM: {}", ex)
            }
        }
    }

    private Map<String, ServicePlan> getXenServerServicePlans() {
        servicePlans ?: (servicePlans = morpheusContext.async.servicePlan.list(new DataQuery().withFilter('provisionTypeCode', 'xen')).toMap { it.code }.blockingGet())
//        servicePlans ?: (servicePlans = morpheusContext.async.servicePlan.list(new DataQuery().withFilter("code","internal-custom-xen")).toMap { it.code }.blockingGet())
    }

    private buildVmConfig(Map cloudItem) {
        def computeServerType = cloudProvider.computeServerTypes.find {
            it.code == 'xenserverUnmanaged'
        }
//        def privateIpAddress = cloudItem.networks.v4?.getAt(0)?.ip_address
//        def publicIpAddress = cloudItem.networks.v4?.getAt(1)?.ip_address

        def vmConfig = [
                account          : cloud.account,
                externalId       : cloudItem.vm.uuid,
                name             : cloudItem.vm.nameLabel,
//                poweredOn        : cloudItem.vm.powerState == com.xensource.xenapi.Types.VmPowerState.RUNNING,
//                resourcePool     : zonePool ? new CloudPool(id: zonePool?.id) : null,
//                externalIp       : publicIpAddress,
//                internalIp       : privateIpAddress,
//                sshHost          : privateIpAddress,
                sshUsername      : 'root',
                provision        : false,
                singleTenant     : true,
                cloud            : cloud,
                lvmEnabled       : false,
                managed          : false,
                serverType       : 'unmanaged',
                status           : 'provisioned',
                uniqueId         : cloudItem.vm.uuid,
                powerState       : cloudItem.powerState == 'Running' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
//                powerState       : cloudItem.powerState == 'active' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
//                powerState       : cloudItem.vm.powerState,
//                maxMemory        : cloudItem.memory * ComputeUtility.ONE_MEGABYTE,
                maxMemory        : cloudItem.vm.memoryTarget,
//                maxCores         : (cloudItem.vcpus?.toLong() ?: 0) * (cloudItem.disk?.toLong() ?: 0),
                maxCores         : cloudItem.vm.VCPUsMax,
                coresPerSocket   : 1l,
                osType           : 'unknown',
                osDevice         : '/dev/vda',
                serverOs         : new OsType(code: 'unknown'),
                apiKey           : java.util.UUID.randomUUID(),
                discovered       : true,
                maxStorage       : cloudItem.totalDiskSize?.toLong() ?: 0,
//              region           : zonePool ? new CloudRegion(id: zonePool?.id) : null,
                computeServerType: computeServerType,
                category         : "xen.vm.${cloud.id}"
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
        List<ComputeServer> saves = []

        for (update in updateList) {
            ComputeServer currentServer = update.existingItem
            Map cloudItem = update.masterItem
            if (currentServer.status != 'provisioning') {
                try {
                    def	maxCpu = cloudItem.vm.VCPUsMax?.toLong() ?: 1
                    log.info("RAZI :: maxCpu: ${maxCpu}")
                    def maxMemory = cloudItem.vm.memoryTarget?.toLong() ?: 0
                    log.info("RAZI :: maxMemory: ${maxMemory}")
                    def maxStorage = cloudItem.totalDiskSize?.toLong() ?: 0
                    log.info("RAZI :: maxStorage: ${maxStorage}")
                    def save = false
//                    def capacityInfo = currentServer.capacityInfo ?: new ComputeCapacityInfo(server:currentServer, maxMemory:maxMemory, maxStorage:maxStorage)
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
                    log.info("RAZI :: currentServer: ${currentServer}")
                    log.info("RAZI :: save: ${save}")
                    if (save) {
                        saves << currentServer
                    }
                } catch (e) {
                    log.error("Error Updating Virtual Machine - ${e}", e)
                }
            }
        }
        log.info("RAZI :: saves: ${saves}")
        if (saves) {
            morpheusContext.async.computeServer.bulkSave(saves).blockingGet()
        }
    }

    def removeMissingVirtualMachines(List<ComputeServerIdentityProjection> removeList) {
        log.debug("removeMissingVirtualMachines: ${cloud} ${removeList.size()}")
        morpheusContext.async.computeServer.remove(removeList).blockingGet()
    }
}
