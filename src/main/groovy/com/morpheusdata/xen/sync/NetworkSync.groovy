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
            //credentials
            //zoneService.loadFullZone(opts.zone)
            def listResults = XenComputeUtility.listNetworks(cloud, plugin)
            //NetworkType networkType = NetworkType.findByCode('xenNetwork') // data query
            //NetworkType networkType = morpheusContext.services.network.list(new DataQuery().withFilter('code', 'xenNetwork'))

            NetworkType networkType = new NetworkType(code: 'xenNetwork')
            log.debug("networks: ${listResults}")
            if (listResults.success == true) {
                def domainRecords = morpheusContext.async.cloud.network.listIdentityProjections(cloud.id)

                SyncTask<NetworkIdentityProjection, Map, Network> syncTask = new SyncTask<>(domainRecords, listResults.networkList as Collection<Map>)
                syncTask.addMatchFunction { NetworkIdentityProjection domainObject, Map cloudItem ->
                    domainObject.externalId == cloudItem?.uuid
                }.onDelete { removeItems ->
                    morpheusContext.async.cloud.network.remove(removeItems).blockingGet()
                }.onUpdate { List<SyncTask.UpdateItem<Network, Map>> updateItems ->
                    updateMatchedNetworks(updateItems, networkType)
                }.onAdd { itemsToAdd ->
                    addMissingNetworks(itemsToAdd, networkType)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
                    morpheusContext.async.cloud.network.listById(updateItems?.collect { it.existingItem.id }).map { Network network ->
                        SyncTask.UpdateItemDto<NetworkIdentityProjection, Map> matchItem = updateItemMap[network.id]
                        return new SyncTask.UpdateItem<NetworkIdentityProjection, Map>(existingItem: network, masterItem: matchItem.masterItem)
                    }
                }.start()
            }
        } catch (e) {
            log.error("cacheNetworks error: ${e}", e)
        }
    }

    private addMissingNetworks(List addList, NetworkType networkType) {
        def networkAdds = []
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
                    zone       : cloud,
                    owner      : new Account(id: cloud.owner.id),
                    description: cloudItem.nameDescription,
                    dhcpServer : true,
            ]
            Network networkAdd = new Network(networkConfig)
            networkAdds << networkAdd
        }
        //create networks
        morpheusContext.async.cloud.network.create(networkAdds).blockingGet()
    }

    private updateMatchedNetworks(List<SyncTask.UpdateItem<Network, Map>> updateList, NetworkType networkType) {
        List<Network> itemsToUpdate = []
        for (updateMap in updateList) {
            def matchedNetwork = updateMap.masterItem
            Network network = updateMap.existingItem
            log.debug "processing update: ${network}"

            if (network) {
                def save = false
                if (!network.type) {
                    network.type = networkType
                    network.dhcpServer = true
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
    }
}
