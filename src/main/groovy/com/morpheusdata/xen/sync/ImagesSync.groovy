package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.VM
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

/**
 * @author razi.ahmad
 */

@Slf4j
class ImagesSync {
    private Cloud cloud
    XenserverPlugin plugin
    private MorpheusContext morpheusContext

    ImagesSync(Cloud cloud, XenserverPlugin plugin) {
        this.cloud = cloud
        this.plugin = plugin
        this.morpheusContext = plugin.morpheusContext
    }

    /**
     * Executes the synchronization process for virtual images.
     */
    def execute() {
        log.debug("ImagesSync >> execute() called")
        try {
            def authConfig = plugin.getAuthConfig(cloud)
            def listResults = XenComputeUtility.listTemplates(authConfig)
            log.debug("templates: ${listResults}")
            if (listResults.success == true) {
                def cloudItems = listResults?.templateList

                Observable<VirtualImageIdentityProjection> existingRecords = morpheusContext.async.virtualImage.listIdentityProjections(
                        new DataQuery()
//                                .withFilter("accounts.id", cloud.account.id) //TODO: this need to be modify after altering Morpheus core api
                                .withFilter('category', "xenserver.image.${cloud.id}")
                )

                SyncTask<VirtualImageIdentityProjection, VM.Record, VirtualImage> syncTask = new SyncTask<>(existingRecords, cloudItems as Collection<VM.Record>)
                syncTask.addMatchFunction {existingItem, cloudItem ->
                    existingItem.externalId == cloudItem.uuid
                }.onDelete { removeItems ->
                    removeMissingVirtualImages(removeItems)
                }.onUpdate {updateItems ->
                    updateMatchedImages(updateItems)
                }.onAdd { itemsToAdd ->
                    addMissingVirtualImages(itemsToAdd)
                }.withLoadObjectDetailsFromFinder { updateItems ->
                    return morpheusContext.async.virtualImage.listById(updateItems.collect { it.existingItem.id } as List<Long>)

                }.start()
            } else {
                log.error("Error not getting the listResults")
            }
        } catch (e) {
            log.error("ImagesSync error: ${e}", e)
        }
    }

    /**
     * Adds missing virtual images to the Morpheus context based on the provided list of VM records.
     *
     * @param addList A collection of VM records representing the virtual images to be added.
     */
    private addMissingVirtualImages(Collection<VM.Record> addList) {
        def imageAdds = []
        try {
            addList?.each { cloudItem ->
                def imageConfig = [
                        category    : "xenserver.image.${cloud.id}",
                        name        : cloudItem.nameLabel,
                        owner       : cloud.owner,
                        description : cloudItem.nameDescription,
                        imageType   : 'xen',
                        code        : "xenserver.image.${cloud.id}.${cloudItem.uuid}",
                        uniqueId    : cloudItem.uuid,
                        status      : 'Active',
                        externalId  : cloudItem.uuid,
                        refType     : 'ComputeZone',
                        refId       : "${cloud.id}"
                ]
                VirtualImage virtualImage = new VirtualImage(imageConfig)
                virtualImage.account
                def locationProps = [
                        virtualImage: virtualImage,
                        code        : "xenserver.image.${cloud.id}.${cloudItem.uuid}",
                        internalId  : virtualImage.externalId,
                        externalId  : virtualImage.externalId,
                        imageName   : virtualImage.name
                ]
                VirtualImageLocation virtualImageLocation = new VirtualImageLocation(locationProps)
                virtualImage.imageLocations = [virtualImageLocation]
                imageAdds << virtualImage
            }
            //create images
            morpheusContext.async.virtualImage.create(imageAdds, cloud).blockingGet()
        } catch (e) {
            log.error("Error in adding Image sync: ${e}", e)
        }
    }

    /**
     * Updates matched virtual images in the Morpheus context based on the provided list of update items.
     *
     * @param updateList A list of SyncTask.UpdateItem objects containing master and existing virtual image records.
     */
    private void updateMatchedImages(List<SyncTask.UpdateItem<VirtualImage, VM.Record>> updateList) {
        try {
            log.debug("updateMatchedImages: ${updateList?.size()}")
            List<VirtualImage> imagesToUpdate = []
            updateList.each { it ->
                def masterItem = it.masterItem
                def existingItem = it.existingItem
                def doSave = false

                if (existingItem.uniqueId != masterItem.uuid) {
                    existingItem.uniqueId = masterItem.uuid
                    doSave = true
                }

                if (doSave) {
                    imagesToUpdate << existingItem
                }
            }

            log.debug("Have ${imagesToUpdate?.size()} to update")
            morpheusContext.async.virtualImage.save(imagesToUpdate, cloud).blockingGet()
        } catch (e) {
            log.error("Error in updateMatchedImages method: ${e}", e)
        }
    }

    private removeMissingVirtualImages(Collection<VirtualImageLocationIdentityProjection> removeList) {
        log.debug ("removeMissingVirtualImages: ${cloud} ${removeList.size()}")
        morpheusContext.async.virtualImage.remove(removeList).blockingGet()
    }
}
