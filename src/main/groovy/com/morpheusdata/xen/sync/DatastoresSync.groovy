package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.projection.DatastoreIdentityProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.SR
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable
/**
 * @author razi.ahmad
 */
@Slf4j
class DatastoresSync {
    private Cloud cloud
    XenserverPlugin plugin
    private MorpheusContext morpheusContext

    DatastoresSync(Cloud cloud, XenserverPlugin plugin) {
        this.cloud = cloud
        this.plugin = plugin
        this.morpheusContext = plugin.morpheusContext
    }

    /**
     * Executes the synchronization process for Datastores.
     */
    def execute() {
        log.debug("DatastoresSync >> execute() called")
        try {
            def authConfig = plugin.getAuthConfig(cloud)
            def listResults = XenComputeUtility.listStorageRepositories(authConfig)
            if (listResults.success == true) {
                def cloudItems = listResults?.srList

                Observable<DatastoreIdentityProjection> existingRecords = morpheusContext.async.cloud.datastore.listIdentityProjections(
                        new DataQuery()
                                .withFilter('owner', cloud.owner)
                                .withFilter('category', "xenserver.sr.${cloud.id}")
                )

                SyncTask<DatastoreIdentityProjection, SR.Record, Datastore> syncTask = new SyncTask<>(existingRecords, cloudItems as Collection<SR.Record>)
                syncTask.addMatchFunction {existingItem, cloudItem ->
                    existingItem.externalId == cloudItem.uuid
                }.onDelete { removeItems ->
                    removeMissingDatastores(removeItems)
                }.onUpdate {updateItems ->
                    updateMatchedDatastores(updateItems)
                }.onAdd { itemsToAdd ->
                    addMissingDatastores(itemsToAdd)
                }.withLoadObjectDetailsFromFinder { updateItems ->
                    return morpheusContext.async.cloud.datastore.listById(updateItems.collect { it.existingItem.id } as List<Long>)

                }.start()
            } else {
                log.error("Error not getting the listResults")
            }
        } catch (e) {
            log.error("ImagesSync error: ${e}", e)
        }
    }

    /**
     * Adds missing datastores to the Morpheus context based on the provided list of SR records.
     *
     * @param addList A collection of SR records representing the datastores to be added.
     */
    private addMissingDatastores(Collection<SR.Record> addList) {
        def dataStoreAdds = []
        try {
            addList?.each { cloudItem ->
                def datastoreConfig = [
                        owner       : cloud.owner,
                        cloud       : cloud,
                        category    : "xenserver.sr.${cloud.id}",
                        name        : cloudItem.nameLabel,
                        code        : "xenserver.sr.${cloud.id}.${cloudItem.uuid}",
                        externalId  : cloudItem.uuid,
                        refType     : 'ComputeZone',
                        refId       : cloud.id,
                        type        : cloudItem.type ?: 'general',
                        storageSize : cloudItem.physicalSize,
                        freeSpace   : cloudItem.physicalSize - cloudItem.physicalUtilisation
                ]
                datastoreConfig.allowWrite = cloudItem.allowedOperations?.find{op -> op == com.xensource.xenapi.Types.StorageOperations.VDI_CREATE} != null
                Datastore datastore = new Datastore(datastoreConfig)
                dataStoreAdds << datastore
            }
            //create dataStore
            morpheusContext.async.cloud.datastore.create(dataStoreAdds).blockingGet()
        } catch (e) {
            log.error("Error in adding DataStore sync: ${e}", e)
        }
    }

    /**
     * Updates matched Datastores in the Morpheus context based on the provided list of update items.
     *
     * @param updateList A list of SyncTask.UpdateItem objects containing master and existing virtual image records.
     */
    private void updateMatchedDatastores(List<SyncTask.UpdateItem<Datastore, SR.Record>> updateList) {
        try {
            log.debug("updateMatchedDatastores: ${updateList?.size()}")
            List<Datastore> dataStoresToUpdate = []
            updateList.each { it ->
                def masterItem = it.masterItem
                def existingItem = it.existingItem
                def doSave = false

                if(existingItem.name != masterItem.nameLabel) {
                    existingItem.name = masterItem.nameLabel
                    doSave = true
                }
                if(existingItem.storageSize != masterItem.physicalSize ) {
                    existingItem.storageSize = masterItem.physicalSize
                    doSave = true
                }

                def freeSpace = masterItem.physicalSize - masterItem.physicalUtilisation
                if(existingItem.freeSpace != freeSpace) {
                    existingItem.freeSpace = freeSpace
                    doSave = true
                }

                if (doSave) {
                    dataStoresToUpdate << existingItem
                }
            }

            log.debug("Have ${dataStoresToUpdate?.size()} to update")
            morpheusContext.async.cloud.datastore.save(dataStoresToUpdate).blockingGet()
        } catch (e) {
            log.error("Error in updateMatchedDatastores method: ${e}", e)
        }
    }

    private removeMissingDatastores(Collection<DatastoreIdentityProjection> removeList) {
        log.debug ("removeMissingDatastores: ${cloud} ${removeList.size()}")
        morpheusContext.async.cloud.datastore.remove(removeList).blockingGet()
    }
}
