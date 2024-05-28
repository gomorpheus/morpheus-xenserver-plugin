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
        log.info("Ray:: NetworkSync: calling")
        try {
            def authConfig = plugin.getAuthConfig(cloud)
            log.info("Ray:: NetworkSync: authConfig: ${authConfig}")
            def listResults = XenComputeUtility.listNetworks(authConfig)
            log.info("Ray:: NetworkSync: listResults: ${listResults}")

            //NetworkType networkType = morpheusContext.services.network.list(new DataQuery().withFilter('code', 'xenNetwork'))
            NetworkType networkType = new NetworkType(code: 'xenNetwork')
            log.info("Ray:: NetworkSync: networkType: ${networkType}")
            log.info("Ray:: NetworkSync: listResults.success: ${listResults.success}")
            if (listResults.success == true) {
                def domainRecords = morpheusContext.async.cloud.network.listIdentityProjections(cloud.id)
                log.info("Ray:: NetworkSync: domainRecords: ${domainRecords}")

                morpheusContext.async.cloud.network.listIdentityProjections(cloud.id).blockingForEach{it->
                    log.info("Rahul :: NetworkSync: domainRecords1 method: printing: ${it}")
                    log.info("Rahul :: NetworkSync: domainRecords1 method: printing externalId: ${it?.externalId}")
                }

                log.info("Ray:: NetworkSync: listResults.networkList: ${listResults.networkList}")
                SyncTask<NetworkIdentityProjection, com.xensource.xenapi.Network.Record, Network> syncTask = new SyncTask<>(domainRecords, listResults.networkList as Collection<com.xensource.xenapi.Network.Record>)
                syncTask.addMatchFunction { NetworkIdentityProjection domainObject, com.xensource.xenapi.Network.Record cloudItem ->
                    log.info("Ray:: NetworkSync: addMatchFunction:domainObject: ${domainObject}")
                    log.info("Ray:: NetworkSync: addMatchFunction:cloudItem: ${cloudItem}")
                    log.info("Ray:: NetworkSync: addMatchFunction:domainObject.externalId: ${domainObject.externalId}")
                    log.info("Ray:: NetworkSync: addMatchFunction:listResults.networkList: ${cloudItem.uuid}")
                    domainObject.externalId == cloudItem.uuid
                }.onDelete { removeItems ->
                    log.info("Ray:: NetworkSync: onDelete:removeItems: ${removeItems}")
                    morpheusContext.async.cloud.network.remove(removeItems).blockingGet()
                }.onUpdate { List<SyncTask.UpdateItem<Network, com.xensource.xenapi.Network.Record>> updateItems ->
                    log.info("Ray:: NetworkSync: onUpdate:updateItems: ${updateItems}")
                    updateMatchedNetworks(updateItems, networkType)
                }.onAdd { itemsToAdd ->
                    log.info("Ray:: NetworkSync: onAdd:itemsToAdd: ${itemsToAdd}")
                    addMissingNetworks(itemsToAdd, networkType)
                }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<NetworkIdentityProjection, com.xensource.xenapi.Network.Record>> updateItems ->
                    log.info("Ray:: NetworkSync: withLoadObjectDetailsFromFinder:updateItems: ${updateItems}")
                    return morpheusContext.async.cloud.network.listById(updateItems.collect { it.existingItem.id } as List<Long>)
                }.start()
            } else {
                log.error("Error not getting the listNetworks")
            }
        } catch (e) {
            log.error("cacheNetworks error: ${e}", e)
        }
    }

    private addMissingNetworks(Collection<com.xensource.xenapi.Network.Record> addList, NetworkType networkType) {
        log.info("Ray:: NetworkSync: addMissingNetworks: addList: ${addList}")
        log.info("Ray:: NetworkSync: addMissingNetworks: networkType: ${networkType}")
        def networkAdds = []
        try {
            addList?.each { cloudItem ->
                log.info("Ray:: NetworkSync: addMissingNetworks: cloudItem: ${cloudItem}")
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
                log.info("Ray:: NetworkSync: addMissingNetworks: networkConfig: ${networkConfig}")
                Network networkAdd = new Network(networkConfig)
                networkAdds << networkAdd
            }
            //create networks
            log.info("Ray:: NetworkSync: addMissingNetworks: networkAdds: ${networkAdds}")
            morpheusContext.async.cloud.network.create(networkAdds).blockingGet()
        } catch (e) {
            log.error "Error in adding Network sync ${e}", e
        }
    }

    private updateMatchedNetworks(List<SyncTask.UpdateItem<Network, com.xensource.xenapi.Network.Record>> updateList, NetworkType networkType) {
        log.info("NetworkSync:updateMatchedNetworks: Entered")
        log.info("Ray:: NetworkSync: updateMatchedNetworks: updateList: ${updateList}")
        log.info("Ray:: NetworkSync: updateMatchedNetworks: networkType: ${networkType}")
        List<Network> itemsToUpdate = []
        try {
            for (update in updateList) {
                Network network = update.existingItem
                log.info("Ray:: NetworkSync: updateMatchedNetworks: network: ${network}")
                log.debug "processing update: ${network}"
                if (network) {
                    def save = false
                    log.info("Ray:: NetworkSync: updateMatchedNetworks: network.type: ${network.type}")
                    if (!network.type) {
                        network.type = networkType
                        network.dhcpServer = true
                        save = true
                    }
					if(network.name != update.masterItem.nameLabel) {
						network.name = update.masterItem.nameLabel
						save = true
					}
					if(network.description != update.masterItem.nameDescription) {
						network.description = update.masterItem.nameDescription
						save = true
					}
                    if (save) {
                        itemsToUpdate << network
                    }
                }
            }
            log.info("Ray:: NetworkSync: updateMatchedNetworks: itemsToUpdate.size(): ${itemsToUpdate.size()}")
            if (itemsToUpdate.size() > 0) {
                morpheusContext.async.cloud.network.save(itemsToUpdate).blockingGet()
            }
        } catch(e) {
            log.error "Error in update Network sync ${e}", e
        }
    }
}