package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.core.util.SyncUtils
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.VM
import groovy.util.logging.Slf4j

@Slf4j
class VirtualMachineSync {

	static final String UNMANAGED_SERVER_TYPE_CODE = 'xenserverUnmanaged'
	static final String HOST_SERVER_TYPE_CODE = 'xenserverHypervisor'

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
                def domainRecords = morpheusContext.async.computeServer.listIdentityProjections(
					new DataQuery().withFilter("zone.id", cloud.id).withFilter("computerServerType.code", "!=", HOST_SERVER_TYPE_CODE)
				)
                Map hosts = getAllHosts()

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
                    addMissingVirtualMachines(itemsToAdd, usageLists, availablePlans, availablePlanPermissions, authConfig, hosts)
                }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, VM.Record>> updateItems ->
                    updateMatchedVirtualMachines(updateItems, authConfig)
                }.onDelete { removeItems ->
                    removeMissingVirtualMachines(removeItems)
                }.observe().blockingSubscribe()
            }
        } catch (Exception ex) {
            log.error("VirtualMachineSync error: {}", ex.getMessage())
        }
    }

    def addMissingVirtualMachines(List addList,Map usageLists, Collection<ServicePlan> availablePlans, Collection<ResourcePermission> availablePlanPermissions, Map authConfig, Map hosts) {
		def doInventory = cloud.getConfigProperty('importExisting')
		if (doInventory == 'on' || doInventory == 'true' || doInventory == true) {
			log.debug("addMissingVirtualMachines ${cloud} ${addList?.size()} ${usageLists}")
			ServicePlan servicePlan = morpheusContext.services.servicePlan.find(new DataQuery().withFilter("code", "internal-custom-xen"))

			for(cloudItem in addList) {
				try {
					def vmConfig = buildVmConfig(cloudItem, servicePlan, availablePlans, availablePlanPermissions, hosts)
					ComputeServer add = new ComputeServer(vmConfig)
					ComputeServer savedServer = morpheusContext.async.computeServer.create(add).blockingGet()
					if(!savedServer) {
						log.error "Error in creating server ${add}"
					} else {
						performPostSaveSync(savedServer, cloudItem, authConfig)
					}
					if(vmConfig.powerState == ComputeServer.PowerState.on) {
						usageLists.startUsageIds << savedServer.id
					} else {
						usageLists.stopUsageIds << savedServer.id
					}
				} catch(Exception ex) {
					log.error("Error in adding VM: {}", ex)
				}
			}
		}
    }

    private buildVmConfig(Map cloudItem, ServicePlan servicePlan, Collection<ServicePlan> availablePlans, Collection<ResourcePermission> availablePlanPermissions, Map hosts) {
        def computeServerType = cloudProvider.computeServerTypes.find { it.code == UNMANAGED_SERVER_TYPE_CODE }

        def powerState = cloudItem.vm.powerState.toString()
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
                powerState       : powerState == 'Running' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
                maxMemory        : cloudItem.vm.memoryTarget,
                maxCores         : cloudItem.vm.VCPUsMax,
                apiKey           : java.util.UUID.randomUUID(),
                maxStorage       : cloudItem.totalDiskSize?.toLong() ?: 0,
                computeServerType: computeServerType,
                category         : "xen.vm.${cloud.id}",
                parentServer     : hosts[cloudItem.hostId],
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

    private Boolean performPostSaveSync(ComputeServer server, Map cloudItem, Map authConfig) {
        log.debug("performPostSaveSync: ${server?.id}")
        def changes = false

        def volumesList = cloudItem.volumes ?: XenComputeUtility.getVmSyncVolumes(authConfig, cloudItem.vm)
        def volumeResults = syncVmVolumes(volumesList, server)
        def vmNetworks = cloudItem.guestMetrics ? cloudItem.guestMetrics['networks'] : [:]
        syncVmNetworks(cloudItem.virtualInterfaces, server, vmNetworks)
        if(volumeResults.maxStorage && server.maxStorage != volumeResults.maxStorage) {
            server.maxStorage = volumeResults.maxStorage
            changes = true
        }
        if(volumeResults.saveRequired == true) {
            changes = true
        }
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

    void updateMatchedVirtualMachines(List<SyncTask.UpdateItem<ComputeServer, Map>> updateList, authConfig) {
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

                    def volumesList = cloudItem.volumes ?: XenComputeUtility.getVmSyncVolumes(authConfig, cloudItem.vm)
                    def volumeResults = syncVmVolumes(volumesList, currentServer)
                    def vmNetworks = cloudItem.guestMetrics ? cloudItem.guestMetrics['networks'] : [:]
                    syncVmNetworks(cloudItem.virtualInterfaces, currentServer, vmNetworks)
                    if(volumeResults.maxStorage && currentServer.maxStorage != volumeResults.maxStorage) {
                        currentServer.maxStorage = volumeResults.maxStorage
                        save = true
                    }

                    //Update the power state of the VM and the status of its associated container/instance
                    def powerState = cloudItem.vm.powerState.toString() == 'Running' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
                    if(currentServer.powerState != powerState) {
                        currentServer.powerState = powerState
                        save = true
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
		def removeItems = morpheusContext.services.computeServer.list(
			new DataQuery().withFilter("id", "in", removeList.collect { it.id }).withFilter("computeServerType.code", UNMANAGED_SERVER_TYPE_CODE)
		)
		morpheusContext.async.computeServer.remove(removeItems).blockingGet()
    }

    private syncVmNetworks(List<Map> nicList, ComputeServer server, Map vmNetworks = [:]) {
        log.debug("syncVmNetworks: ${nicList?.size()}")
        def rtn = [success: false, saveRequired: false, maxStorage: 0]
        try {
            //ignore servers that are being resized
            if (server.status == 'resizing') {
                log.warn("Ignoring server {} because it is resizing", server)
            } else {
                log.debug("Interface List {}", nicList)
                // return rtn
                def matchFunc = { ComputeServerInterface morpheusInterface, nicInfo ->
                    morpheusInterface?.internalId == nicInfo.uuid
                }
                def existingItems = server.interfaces
                def syncLists = XenComputeUtility.buildSyncLists(existingItems, nicList, matchFunc)
                syncLists.addList?.each { nicInfo ->
                    def newInterface = new ComputeServerInterface(
                            externalId   : nicInfo.uuid,
                            name         : "eth${nicInfo.deviceIndex}",
                            internalId   : nicInfo.uuid,
                            macAddress   : nicInfo.macAddress,
                            displayOrder : nicInfo.deviceIndex?.toInteger()
                    )
                    if (nicInfo.deviceIndex == 0) {
                        newInterface.primaryInterface = true
                    } else {
                        newInterface.primaryInterface = false
                    }
                    def addresses = []
                    vmNetworks?.each { key, val ->
                        if (key.startsWith("${nicInfo.deviceIndex}/ipv4")) {
                            addresses << [address: val, type: NetAddress.AddressType.IPV4]
                        } else if (key.startsWith("${nicInfo.deviceIndex}/ipv6")) {
                            addresses << [address: val, type: NetAddress.AddressType.IPV6]
                        }
                    }
                    addresses?.each { addr ->
                        newInterface.addresses << new NetAddress(addr)
                    }
                    def network = morpheusContext.services.network.find(
                            new DataQuery().withFilter('refType', 'ComputeZone').withFilter('refId', cloud.id)
                                    .withFilter('externalId', "${nicInfo.networkUuid}"))
                    if (network) {
                        newInterface.network = network
                    }
                    morpheusContext.async.computeServer.computeServerInterface.create([newInterface], server).blockingGet()
                    rtn.saveRequired = true

                }
                // Process updates
                syncLists.updateList?.each { updateMap ->
                    log.debug("processing update item: ${updateMap}")
                    ComputeServerInterface existingNic = updateMap.existingItem
                    def nicInfo = updateMap.masterItem
                    Integer deviceIndex = nicInfo.deviceIndex?.toInteger()

                    def addresses = []
                    vmNetworks?.each { key, val ->
                        if (key.startsWith("${nicInfo.deviceIndex}/ipv4")) {
                            addresses << [address: val, type: NetAddress.AddressType.IPV4]
                        } else if (key.startsWith("${nicInfo.deviceIndex}/ipv6")) {
                            addresses << [address: val, type: NetAddress.AddressType.IPV6]
                        }
                    }
                    def addressMatchFunction = { morpheusItem, Map cloudItem ->
                        morpheusItem.address == cloudItem.address
                    }
                    def save = false
                    def addressSyncList = XenComputeUtility.buildSyncLists(existingNic.addresses, addresses, addressMatchFunction)
                    addressSyncList.removeList?.each { NetAddress netAddress ->
                        def currentAddresses = existingNic.addresses
                        if (currentAddresses) {
                            currentAddresses.remove(netAddress)
                        }
                        save = true
                    }
                    addressSyncList.addList?.each { Map cloudItem ->
                        existingNic.addresses << new NetAddress(cloudItem)
                        save = true
                    }
                    Boolean primaryInterface = nicInfo.deviceIndex.toString() == '0'
                    if (existingNic.primaryInterface != primaryInterface) {
                        existingNic.primaryInterface = primaryInterface
                        save = true
                    }
                    def network = morpheusContext.services.network.find(
                            new DataQuery().withFilter('refType', 'ComputeZone').withFilter('refId', cloud.id)
                                    .withFilter('externalId', "${nicInfo.networkUuid}"))
                    if (existingNic.network?.id != network?.id) {
                        existingNic.network = network
                        save = true
                    }
                    if (existingNic.displayOrder != deviceIndex) {
                        existingNic.displayOrder = deviceIndex
                        save = true
                    }
                    if (existingNic.macAddress != nicInfo.macAddress) {
                        existingNic.macAddress = nicInfo.macAddress
                        save = true
                    }
                    if (save) {
                        rtn.saveRequired = true
                        morpheusContext.async.computeServer.computeServerInterface.save([existingNic]).blockingGet()
                    }
                }
                // Process removes
                if (syncLists.removeList?.size() > 0) {
                    morpheusContext.async.computeServer.computeServerInterface.remove(syncLists.removeList, server).blockingGet()
                    rtn.saveRequired = true
                }
            }
            rtn.success = true
        } catch (e) {
            log.error("error syncVmNetworks ${e}", e)
        }
        return rtn
    }

    private syncVmVolumes(List diskList, ComputeServer server) {
        log.debug("syncVmVolumes: ${diskList?.size()}")
        def rtn = [success: false, saveRequired: false, maxStorage: 0]
        def createList = []
        def saveList = []
        try {
            //ignore servers that are being resized
            if (server.status == 'resizing') {
                log.warn("Ignoring server {} because it is resizing", server)
            } else {
                def storageVolumeType = cloudProvider.storageVolumeTypes.find { it.code == "standard" }
                def matchFunc = { StorageVolume morpheusVolume, diskInfo ->
                    (morpheusVolume?.internalId == diskInfo.uuid || morpheusVolume.unitNumber.toString() == diskInfo.deviceIndex.toString())
                }
                def existingItems = server.volumes
                def syncLists = XenComputeUtility.buildSyncLists(existingItems, diskList, matchFunc)
                syncLists.addList?.each { diskInfo ->
                    def volumeId = diskInfo.uuid
                    def maxStorage = diskInfo.size
                    def deviceName = diskInfo.deviceName
                    def dataStoreExId = diskInfo.dataStore?.externalId
                    def volume = new StorageVolume(
                            [
                                    maxStorage  : maxStorage,
                                    type        : storageVolumeType,
                                    externalId  : diskInfo.deviceIndex,
                                    internalId  : volumeId,
                                    unitNumber  : diskInfo.deviceIndex,
                                    deviceName  : deviceName ? "/dev/${deviceName}" : null,
                                    name        : (diskInfo.deviceName ?: volumeId),
                                    refId       : cloud.id,
                                    displayOrder: diskInfo.displayOrder,
                                    account     : server.account,
                                    refType     : 'ComputeZone',
                                    status      : 'provisioned'
                            ])
                    volume.deviceDisplayName = deviceName
                    if (volume.deviceDisplayName == 'xvda') {
                        volume.rootVolume = true
                    }
                    if (dataStoreExId) {
                        def datastore = morpheusContext.services.cloud.datastore.find(
                                new DataQuery().withFilter("code", "xenserver.sr.${server.cloud.id}.${dataStoreExId}"))
                        if (datastore) {
                            volume.datastore = datastore
                        }
                    }
                    createList << volume
                    rtn.saveRequired = true
                    rtn.maxStorage += maxStorage
                }
                if (createList) {
                    morpheusContext.async.storageVolume.create(createList, server).blockingGet()
                }
                // Process updates
                syncLists.updateList?.each { updateMap ->
                    log.info("processing update item: ${updateMap}")
                    StorageVolume existingVolume = updateMap.existingItem
                    def diskInfo = updateMap.masterItem

                    def save = false
                    def maxStorage = diskInfo.size
                    if (existingVolume.maxStorage != maxStorage) {
                        existingVolume.maxStorage = maxStorage
                        save = true
                    }
                    if (existingVolume.unitNumber != diskInfo.deviceIndex?.toString()) {
                        existingVolume.unitNumber = diskInfo.deviceIndex.toString()
                        save = true
                    }
                    def rootVolume = diskInfo.deviceName == 'xvda'
                    if (rootVolume != existingVolume.rootVolume) {
                        existingVolume.rootVolume = rootVolume
                        save = true
                    }
                    if (save) {
                        rtn.saveRequired = true
                        saveList << existingVolume
                    }
                    rtn.maxStorage += maxStorage
                }
                if (saveList) {
                    morpheusContext.async.storageVolume.save(saveList).blockingGet()
                }
                // Process removes
                if (syncLists.removeList) {
                    morpheusContext.async.storageVolume.remove(syncLists.removeList, server, false).blockingGet()
                    rtn.saveRequired = true
                }
            }
            rtn.success = true
        } catch (e) {
            log.error("error syncVmVolumes ${e}", e)
        }
        return rtn
    }

    private Map getAllHosts() {
        log.debug "getAllHosts: ${cloud}"
        def hostIdentitiesMap = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null).filter {
            it.computeServerTypeCode == HOST_SERVER_TYPE_CODE
        }.toMap { it.externalId }.blockingGet()
        hostIdentitiesMap
    }
}
