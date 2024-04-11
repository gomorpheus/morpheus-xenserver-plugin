package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.util.XenComputeUtility
import groovy.util.logging.Slf4j

/**
 * @author rahul.ray
 */

@Slf4j
class HostSync {

    private Cloud cloud
    XenserverPlugin plugin
    private MorpheusContext morpheusContext

    HostSync(Cloud cloud, XenserverPlugin plugin) {
        this.cloud = cloud
        this.plugin = plugin
        this.morpheusContext = plugin.morpheusContext

    }

    def execute() {
        log.debug "HostSync"
        log.info("Rahul::HostSync: execute: Entered")
        try {

            def listResults = XenComputeUtility.listHosts(plugin.getAuthConfig(cloud))
            log.info("Rahul::HostSync: execute: listResults: ${listResults}")
            log.debug("host list: {}", listResults)
            if (listResults.success == true) {
                /*def domainRecords = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, null).filter {
                    ComputeServerIdentityProjection projection -> "xen.host.${cloud.id}".equalsIgnoreCase(projection.category)
                }*/

                /*def domainRecords = morpheusContext.async.computeServer.listIdentityProjections(
                        new DataQuery().withFilters([new DataFilter('zone.id', cloud.id)])
                )*/

                def domainRecords = morpheusContext.async.computeServer.listIdentityProjections(
                        new DataQuery().withFilter('zone.id', cloud.id)
                )
                log.info("Rahul::HostSync: execute: domainRecords: ${domainRecords}")
                log.info("Rahul :: HostSync domainRecords2: ${domainRecords.map { "${it.externalId} - ${it.name}" }.toList().blockingGet()}")
                SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, listResults.hostList as Collection<Map>)
                syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
                    log.info("Rahul::HostSync: execute: cloudItem: ${cloudItem}")
                    log.info("Rahul::HostSync: execute: domainObject.externalId: ${domainObject.externalId}")
                    log.info("Rahul::HostSync: execute: cloudItem?.uuid: ${cloudItem?.uuid}")
                    domainObject.externalId == cloudItem?.uuid
                }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
                    morpheusContext.async.computeServer.listById(updateItems.collect { it.existingItem.id } as List<Long>)
                }.onAdd { itemsToAdd ->
                    addMissingHosts(itemsToAdd)
                }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
                    updateMatchedHosts(updateItems)
                }.onDelete { removeItems ->
                    removeMissingHosts(removeItems)
                }.start()
            } else {
                log.error "Error in getting disks : ${listResults}"
            }
        } catch (e) {
            log.error("cacheHosts error: ${e}", e)
        }
    }

    private updateMatchedHosts(List<SyncTask.UpdateItem<ComputeServer, Map>> updateList) {
        log.debug "updateMatchedHosts: ${cloud.id} ${updateList.size()}"
        log.info("Rahul::HostSync: updateMatchedHosts: updateList.size(): ${updateList.size()}")
        def saves = []
        def doSave = false
        try {
            for (updateItem in updateList) {
                doSave = false
                log.info("Rahul::HostSync: updateMatchedHosts: updateItem: ${updateItem}")
                ComputeServer existingItem = updateItem.existingItem
                log.info("Rahul::HostSync: updateMatchedHosts: existingItem?.name: ${existingItem?.name}")
                def status = updateItem.masterItem?.status
                log.info("Rahul::HostSync: updateMatchedHosts: status: ${status}")
                log.info("Rahul::HostSync: updateMatchedHosts: existingItem?.status: ${existingItem?.status}")
                if (existingItem?.status != status) {
                    existingItem.status = status
                    doSave = true
                }
                log.info("Rahul::HostSync: updateMatchedHosts: existingItem?.status1: ${existingItem?.status}")
                /*def status = updateMap.masterItem.status
                if(updateMap.existingItem?.status != status) {
                    updateMap.existingItem.status = status
                    doSave = true
                }*/
                log.info("Rahul::HostSync: updateMatchedHosts: doSave: ${doSave}")
                if (doSave == true) {
                    saves << existingItem
                }
            }
            log.info("Rahul::HostSync: updateMatchedHosts: saves.size(): ${saves.size()}")
            if (saves) {
                //morpheusContext.async.computeServer.bulkSave(saves).blockingGet()
                log.info("Rahul::HostSync: updateMatchedHosts: Going to update records")
            }
        } catch (e) {
            log.error("updateMatchedHosts error: ${e}", e)
        }
    }

    private addMissingHosts(Collection<Map> addList) {
        log.debug "addMissingHosts: ${cloud} ${addList.size()}"
        log.info("Rahul::HostSync: addMissingHosts: addList.size(): ${addList.size()}")
        def hostAdds = []

        try {
            def serverType = new ComputeServerType(code: 'xenserverHypervisor')
            def serverOs = new OsType(code: 'linux.64')
            for (cloudItem in addList) {
                def serverConfig =
                        [
                                account          : cloud.owner,
                                category         : "xen.host.${cloud.id}",
                                name             : cloudItem.host.nameLabel,
                                externalId       : cloudItem.uuid,
                                cloud            : cloud,
                                sshUsername      : 'root',
                                apiKey           : java.util.UUID.randomUUID(),
                                status           : 'provisioned',
                                provision        : false,
                                singleTenant     : false,
                                serverType       : 'hypervisor',
                                computeServerType: serverType,
                                statusDate       : new Date(),
                                serverOs         : serverOs,
                                osType           : 'linux',
                                hostname         : cloudItem.host.hostname
                        ]
                log.info("Rahul::HostSync: addMissingHosts: serverConfig: ${serverConfig}")
                def newServer = new ComputeServer(serverConfig)
                log.info("Rahul::HostSync: addMissingHosts: cloudItem.host: ${cloudItem.host}")
                log.info("Rahul::HostSync: addMissingHosts: cloudItem.host?.address: ${cloudItem.host?.address}")
                if (cloudItem.host.address) {
                    newServer.internalIp = cloudItem.host.address
                    newServer.externalIp = cloudItem.host.address
                    newServer.sshHost = cloudItem.host.address
                }
                log.info("Rahul::HostSync: addMissingHosts: cloudItem.metrics: ${cloudItem.metrics}")
                def serverMetrics = cloudItem.metrics
                log.info("Rahul::HostSync: addMissingHosts: serverMetrics?.dump(): ${serverMetrics?.dump()}")
                log.debug("metrics: {}", serverMetrics?.dump())
                newServer.maxMemory = serverMetrics?.memoryTotal ?: 0
                newServer.maxStorage = 0l
                newServer.capacityInfo = new ComputeCapacityInfo(maxMemory: newServer.maxMemory, maxStorage: newServer.maxStorage)
                morpheusContext.async.computeServer.create(newServer).blockingGet()
                log.info("Rahul::HostSync: addMissingHosts: Going to add records")
            }
        } catch (e) {
            log.error("addMissingHosts error: ${e}", e)
        }
    }

    def removeMissingHosts(List removeList) {
        log.debug "removeMissingHosts: ${removeList.size()}"
        //morpheusContext.async.computeServer.remove(removeList).blockingGet()
    }
}



