package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.util.XenComputeUtility
import groovy.util.logging.Slf4j

/**
 * @author rahul.ray
 */

@Slf4j
class NetworkSync {

    private Cloud cloud
    XenserverPlugin plugin
    private MorpheusContext morpheusContext

    NetworkSync(Cloud cloud, XenserverPlugin plugin) {
        this.cloud = cloud
        this.plugin = plugin
        this.morpheusContext = plugin.morpheusContext
    }

    def execute() {
        log.debug "NetworkSync"
        log.info("Rahul:: NetworkSync:execute: Entered")
        try {
            //credentials
            //zoneService.loadFullZone(opts.zone)
            log.info("Rahul:: NetworkSync:execute: calling XenComputeUtility.listNetworks for cloud ${cloud.id}")
            def listResults = XenComputeUtility.listNetworks(cloud, plugin)
            log.info("Rahul:: NetworkSync:execute: listResults: ${listResults}")
            log.info("Rahul:: NetworkSync:execute: listResults.success: ${listResults.success}")
            //NetworkType networkType = NetworkType.findByCode('xenNetwork') // data query
            //NetworkType networkType = morpheusContext.services.network.list(new DataQuery().withFilter('code', 'xenNetwork'))

            NetworkType networkType = new NetworkType(code: 'xenNetwork')
            log.info("Rahul:: NetworkSync:execute: networkType: ${networkType}")
            log.debug("networks: ${listResults}")
            if (listResults.success == true) {
                def domainRecords = morpheusContext.async.cloud.network.listIdentityProjections(cloud.id)
                log.info("Rahul:: NetworkSync:execute: domainRecords: ${domainRecords}")
                log.info("Rahul:: NetworkSync:execute: domainRecordsMap: ${domainRecords.map { "${it.externalId} - ${it.name}" }.toList().blockingGet()}")

                log.info("Rahul:: NetworkSync:execute: listResults.networkList: ${listResults.networkList}")
                SyncTask<NetworkIdentityProjection, com.xensource.xenapi.Network.Record, Network> syncTask = new SyncTask<>(domainRecords, listResults.networkList as Collection<com.xensource.xenapi.Network.Record>)
                syncTask.addMatchFunction { NetworkIdentityProjection domainObject, com.xensource.xenapi.Network.Record cloudItem ->
                    log.info("Rahul:: NetworkSync:execute: cloudItem: ${cloudItem}")
                    log.info("Rahul:: NetworkSync:execute: domainObject.externalId: ${domainObject.externalId}")
                    log.info("Rahul:: NetworkSync:execute: cloudItem?.uuid: ${cloudItem.uuid}")
                    domainObject.externalId == cloudItem.uuid
                }.onDelete { removeItems ->
                    log.info("Rahul:: NetworkSync:execute: onDelete: ${removeItems}")
                    morpheusContext.async.cloud.network.remove(removeItems).blockingGet()
                }.onUpdate { List<SyncTask.UpdateItem<Network, com.xensource.xenapi.Network.Record>> updateItems ->
                    log.info("Rahul:: NetworkSync:execute: onUpdate: ${updateItems}")
                    updateMatchedNetworks(updateItems, networkType)
                }.onAdd { itemsToAdd ->
                    log.info("Rahul:: NetworkSync:execute: onAdd: ${itemsToAdd}")
                    addMissingNetworks(itemsToAdd, networkType)
                }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<NetworkIdentityProjection, com.xensource.xenapi.Network.Record>> updateItems ->
                    return morpheusContext.async.cloud.network.listById(updateItems.collect { it.existingItem.id } as List<Long>)
                }.start()
            }
        } catch (e) {
            log.error("cacheNetworks error: ${e}", e)
        }
    }

    private addMissingNetworks(Collection<com.xensource.xenapi.Network.Record> addList, NetworkType networkType) {
        log.info("Rahul:: NetworkSync:addMissingNetworks: Entered")
        log.info("Rahul:: NetworkSync:addMissingNetworks: cloud.id: ${cloud.id}")
        log.info("Rahul:: NetworkSync:addMissingNetworks: cloud.owner.id: ${cloud.owner.id}")
        def networkAdds = []
        try {
            addList?.each { cloudItem ->
                log.info("Rahul:: NetworkSync:addMissingNetworks: cloudItem: ${cloudItem}")
                def networkConfig = [
                        category   : "xenserver.network.${cloud.id}",
                        name       : cloudItem.nameLabel ?: cloudItem.uuid,
                        code       : "xenserver.network.${cloud.id}.${cloudItem.uuid}",
                        uniqueId   : cloudItem.uuid,
                        externalId : cloudItem.uuid,
                        refType    : 'ComputeZone',
                        refId      : cloud.id,
                        type       : networkType,
                        owner      : new Account(id: cloud.owner.id),
                        description: cloudItem.nameDescription,
                        dhcpServer : true,
                ]
                log.info("Rahul:: NetworkSync:addMissingNetworks: networkConfig: ${networkConfig}")
                Network networkAdd = new Network(networkConfig)
                networkAdds << networkAdd
            }
            //create networks
            morpheusContext.async.cloud.network.create(networkAdds).blockingGet()
        } catch (e) {
            log.error "Error in adding Network sync ${e}", e
        }
    }

    private updateMatchedNetworks(List<SyncTask.UpdateItem<Network, com.xensource.xenapi.Network.Record>> updateList, NetworkType networkType) {
        log.info("Rahul:: NetworkSync:updateMatchedNetworks: Entered")
        List<Network> itemsToUpdate = []
        try {
            for (update in updateList) {
                log.info("Rahul:: NetworkSync:updateMatchedNetworks: updateMap: ${update}")
                Network network = update.existingItem
                log.debug "processing update: ${network}"
                log.info("Rahul:: NetworkSync:updateMatchedNetworks: processing update: ${network}")

                if (network) {
                    def save = false
                    if (!network.type) {
                        network.type = networkType
                        network.dhcpServer = true
                        save = true
                    }
                    log.info("Rahul:: NetworkSync:updateMatchedNetworks: save: ${save}")
                    if (save) {
                        itemsToUpdate << network
                    }
                }
            }
            log.info("Rahul:: NetworkSync:updateMatchedNetworks: itemsToUpdate.size(): ${itemsToUpdate.size()}")
            if (itemsToUpdate.size() > 0) {
                morpheusContext.async.cloud.network.save(itemsToUpdate).blockingGet()
            }
        } catch(e) {
            log.error "Error in update Network sync ${e}", e
        }
    }
}
