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

	static final String HOST_SERVER_TYPE_CODE = 'xenserverHypervisor'

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
        try {
            def listResults = XenComputeUtility.listHosts(plugin.getAuthConfig(cloud))
            if (listResults.success == true) {
                def domainRecords = morpheusContext.async.computeServer.listIdentityProjections(
					new DataQuery().withFilter("zone.id", cloud.id.toLong())
						.withFilter("computeServerType.code", HOST_SERVER_TYPE_CODE)
						.withFilter("category", "xen.host.${cloud.id}")
				)

				SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, listResults.hostList as Collection<Map>)
                syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map cloudItem ->
                    domainObject.externalId == cloudItem?.uuid
                }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<ComputeServerIdentityProjection, Map>> updateItems ->
                    morpheusContext.async.computeServer.listById(updateItems.collect { it.existingItem.id } as List<Long>)
                }.onAdd { itemsToAdd ->
					log.debug("HostSync, onAdd: ${itemsToAdd}")
                    addMissingHosts(itemsToAdd)
                }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
					log.debug("HostSync, onUpdate: ${updateItems}")
					updateMatchedHosts(updateItems)
                }.onDelete { removeItems ->
					log.debug("HostSync, onDelete: ${removeItems}")
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
        def saves = []
        def doSave = false
        try {
            for (updateItem in updateList) {
                doSave = false
                ComputeServer existingItem = updateItem.existingItem
                def status = updateItem.masterItem?.status
                if (existingItem?.status != status) {
                    existingItem.status = status
                    doSave = true
                }

                if (doSave == true) {
                    saves << existingItem
                }
            }
            if (saves) {
                //morpheusContext.async.computeServer.bulkSave(saves).blockingGet()
            }
        } catch (e) {
            log.error("updateMatchedHosts error: ${e}", e)
        }
    }

    private addMissingHosts(Collection<Map> addList) {
        log.debug "addMissingHosts: ${cloud} ${addList.size()}"
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
                def newServer = new ComputeServer(serverConfig)
                if (cloudItem.host.address) {
                    newServer.internalIp = cloudItem.host.address
                    newServer.externalIp = cloudItem.host.address
                    newServer.sshHost = cloudItem.host.address
                }
                def serverMetrics = cloudItem.metrics
                 log.debug("metrics: {}", serverMetrics?.dump())
                newServer.maxMemory = serverMetrics?.memoryTotal ?: 0
                newServer.maxStorage = 0l
                newServer.capacityInfo = new ComputeCapacityInfo(maxMemory: newServer.maxMemory, maxStorage: newServer.maxStorage)
                morpheusContext.async.computeServer.create(newServer).blockingGet()
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



