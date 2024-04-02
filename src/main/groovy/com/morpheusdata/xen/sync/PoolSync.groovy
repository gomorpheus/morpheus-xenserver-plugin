package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ReferenceData
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.Host
import groovy.util.logging.Slf4j

/**
 * @author rahul.ray
 */

@Slf4j
class PoolSync {

    private Cloud cloud
    XenserverPlugin plugin
    private MorpheusContext morpheusContext

    PoolSync(Cloud cloud, XenserverPlugin plugin) {
        this.cloud = cloud
        this.plugin = plugin
        this.morpheusContext = plugin.morpheusContext
    }

    def execute() {
        log.debug "PoolSync"
        try {
            def listResults = XenComputeUtility.listPools(plugin.getAuthConfig(cloud))

            if (listResults.success == true) {
                def domainRecords = morpheusContext.async.referenceData.listIdentityProjections(
                        new DataQuery().withFilters([new DataFilter('account.id', cloud.owner.id), new DataFilter('category', "xenserver.pool.${cloud.id}")])
                )

                SyncTask<ReferenceDataSyncProjection, Map, ReferenceData> syncTask = new SyncTask<>(domainRecords, listResults.poolList as Collection<Map>)
                syncTask.addMatchFunction { ReferenceDataSyncProjection domainObject, Map cloudItem ->
                    domainObject.externalId == cloudItem.uuid
                }.onDelete { removeItems ->
                    morpheusContext.services.referenceData.bulkRemove(removeItems)
                }.onAdd { itemsToAdd ->
                    addMissingPools(itemsToAdd)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
                    morpheusContext.async.referenceData.listById(updateItems?.collect { it.existingItem.id }).map { ReferenceData referenceData ->
                        SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map> matchItem = updateItemMap[referenceData.id]
                        return new SyncTask.UpdateItem<ReferenceData, Map>(existingItem: referenceData, masterItem: matchItem.masterItem)
                    }
                }.onUpdate { updateList ->
                    //update the master on the cloud
                    setCloudMaster(updateList)
                }.start()
            } else {
                log.error("Error not getting the PoolSync")
            }
        } catch (e) {
            log.error("PoolSync error: ${e}", e)
        }
    }

    def setCloudMaster(List<SyncTask.UpdateItem<ReferenceData, Map>> updateItems) {
        log.debug("PoolSync setCloudMaster:")
        try {
            for (update in updateItems) {
                def cloudItem = update.masterItem
                Host.Record hostRecord = cloudItem?.master
                def masterAddress = hostRecord?.address
                if (masterAddress) {
                    cloud.setConfigProperty('masterAddress', masterAddress)
                    morpheusContext.services.cloud.save(cloud)
                }
            }
        } catch (e) {
            log.error("PoolSync error in setCloudMaster: ${e}", e)
        }
    }

    private addMissingPools(Collection<Map> addList) {
        log.debug("PoolSync:addMissingPools: addList.size(): ${addList.size()}")
        def poolAdds = []
        try {
            addList?.each { cloudItem ->
                def saveConfig = [
                        account    : cloud.account,
                        category   : "xenserver.pool.${cloud.id}",
                        name       : cloudItem.pool.nameLabel ?: 'default',
                        code       : "xenserver.pool.${cloud.id}.${cloudItem.uuid}",
                        keyValue   : cloudItem.uuid,
                        refType    : 'ComputeZone',
                        refId      : "${cloud.id}",
                        value      : cloudItem.pool.nameLabel ?: 'default',
                        description: cloudItem.pool.nameDescription,
                        type       : 'string',
                        externalId : cloudItem.uuid
                ]
                ReferenceData referenceData = new ReferenceData(saveConfig)
                poolAdds << referenceData
            }
            morpheusContext.services.referenceData.bulkCreate(poolAdds)
        } catch (e) {
            log.error "Error in adding PoolSync ${e}", e
        }
    }
}