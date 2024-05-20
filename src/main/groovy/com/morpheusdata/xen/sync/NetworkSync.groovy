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
        try {
            def authConfig = plugin.getAuthConfig(cloud)
            def listResults = XenComputeUtility.listNetworks(authConfig)

            //NetworkType networkType = morpheusContext.services.network.list(new DataQuery().withFilter('code', 'xenNetwork'))
            NetworkType networkType = new NetworkType(code: 'xenNetwork')
            if (listResults.success == true) {
                def domainRecords = morpheusContext.async.cloud.network.listIdentityProjections(cloud.id)

                SyncTask<NetworkIdentityProjection, com.xensource.xenapi.Network.Record, Network> syncTask = new SyncTask<>(domainRecords, listResults.networkList as Collection<com.xensource.xenapi.Network.Record>)
                syncTask.addMatchFunction { NetworkIdentityProjection domainObject, com.xensource.xenapi.Network.Record cloudItem ->
                    domainObject.externalId == cloudItem.uuid
                }.onDelete { removeItems ->
                    morpheusContext.async.cloud.network.remove(removeItems).blockingGet()
                }.onUpdate { List<SyncTask.UpdateItem<Network, com.xensource.xenapi.Network.Record>> updateItems ->
                    updateMatchedNetworks(updateItems, networkType)
                }.onAdd { itemsToAdd ->
                    addMissingNetworks(itemsToAdd, networkType)
                }.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<NetworkIdentityProjection, com.xensource.xenapi.Network.Record>> updateItems ->
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
        def networkAdds = []
        try {
            addList?.each { cloudItem ->
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
        log.debug("NetworkSync:updateMatchedNetworks: Entered")
        List<Network> itemsToUpdate = []
        try {
            for (update in updateList) {
                Network network = update.existingItem
                log.debug "processing update: ${network}"
                if (network) {
                    def save = false
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
            if (itemsToUpdate.size() > 0) {
                morpheusContext.async.cloud.network.save(itemsToUpdate).blockingGet()
            }
        } catch(e) {
            log.error "Error in update Network sync ${e}", e
        }
    }
}