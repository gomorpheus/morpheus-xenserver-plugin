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
        log.info("Rahul::PoolSync:execute: Entered in PoolSync")
        try {
            def listResults = XenComputeUtility.listPools(plugin.getAuthConfig(cloud))
            log.info("Rahul::PoolSync:execute: listResults: ${listResults}")
            log.info("Rahul::PoolSync:execute: listResults.success: ${listResults.success}")
            log.info("Rahul::PoolSyncexecute: listResults.pool: ${listResults.pool}")

            if (listResults.success == true) {
                def domainRecords = morpheusContext.async.referenceData.listIdentityProjections(
                        new DataQuery().withFilters([new DataFilter('account.id', cloud.owner.id), new DataFilter('category', "xenserver.pool.${cloud.id}")])
                )
                log.info("Rahul::PoolSync:execute: domainRecords: ${domainRecords}")
                log.info("Rahul::PoolSync:execute: ${domainRecords.map { "${it.externalId} - ${it.name}" }.toList().blockingGet()}")
                SyncTask<ReferenceDataSyncProjection, Map, ReferenceData> syncTask = new SyncTask<>(domainRecords, listResults.poolList as Collection<Map>)
                syncTask.addMatchFunction { ReferenceDataSyncProjection domainObject, Map cloudItem ->
                    log.info("Rahul::PoolSync:execute:addMatchFunction: cloudItem: ${cloudItem}")
                    log.info("Rahul::PoolSync:execute:addMatchFunction: domainObject.externalId: ${domainObject.externalId}")
                    log.info("Rahul::PoolSync:execute:addMatchFunction: cloudItem.uuid: ${cloudItem.uuid}")
                    domainObject.externalId == cloudItem.uuid
                }.onDelete { removeItems ->
                    log.info("Rahul::PoolSync:execute:onDelete: removeItems: ${removeItems}")
                    morpheusContext.services.referenceData.bulkRemove(removeItems)
                }.onAdd { itemsToAdd ->
                    log.info("Rahul::PoolSync:execute:onAdd: itemsToAdd: ${itemsToAdd}")
                    addMissingPools(itemsToAdd)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
                    morpheusContext.async.referenceData.listById(updateItems?.collect { it.existingItem.id }).map { ReferenceData referenceData ->
                        SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Map> matchItem = updateItemMap[referenceData.id]
                        return new SyncTask.UpdateItem<ReferenceData, Map>(existingItem: referenceData, masterItem: matchItem.masterItem)
                    }
                }.onUpdate { updateList ->
                    // No updates.. just add/remove
                }.start()
            } else {
                log.error("Error not getting the listNetworks")
            }
        } catch (e) {
            log.error("cacheNetworks error: ${e}", e)
        }
    }


    private addMissingPools(Collection<Map> addList) {
        log.info("Rahul::PoolSync:execute:addMissingPools: addList.size(): ${addList.size()}")
        def poolAdds = []
        try {
            addList?.each { cloudItem ->
                log.info("Rahul::PoolSync:execute:addMissingPools: cloudItem: ${cloudItem}")
                def saveConfig = [
                        account    : cloud.account,
                        //zone        : opts.zone,
                        category   : "xenserver.pool.${cloud.id}",
                        name       : cloudItem.pool.nameLabel ?: 'default',
                        code       : "xenserver.pool.${cloud.id}.${cloudItem.uuid}",
                        keyValue   : cloudItem.uuid,
                        refType    : 'ComputeZone',
                        refId      : "${cloud.id}",
                        value      : cloudItem.pool.nameLabel ?: 'default',
                        description: cloudItem.pool.nameDescription
                ]
                log.info("Rahul::PoolSync:execute:addMissingPools: saveConfig: ${saveConfig}")
                ReferenceData referenceData = new ReferenceData(saveConfig)
                poolAdds << referenceData
            }
            log.info("Rahul::PoolSync:execute:addMissingPools: poolAdds.size(): ${poolAdds.size()}")
            morpheusContext.services.referenceData.bulkCreate(poolAdds)
        } catch (e) {
            log.error "Error in adding Network sync ${e}", e
        }
    }
}