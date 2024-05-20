package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ImageType
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

                Observable<VirtualImageLocationIdentityProjection> domainRecords = morpheusContext.async.virtualImage.location.listSyncProjections(cloud.id)
                SyncTask<VirtualImageLocationIdentityProjection, VM.Record, VirtualImageLocation> syncTask = new SyncTask<>(domainRecords, cloudItems as Collection<VM.Record>)
                syncTask.addMatchFunction {existingItem, cloudItem ->
                    existingItem.externalId == cloudItem.uuid
                }.onDelete { removeItems ->
                    removeMissingVirtualImageLocations(removeItems)
                }.onUpdate {updateItems ->
                    updateMatchedVirtualImageLocations(updateItems)
                }.onAdd { itemsToAdd ->
                    addMissingVirtualImageLocations(itemsToAdd)
                }.withLoadObjectDetailsFromFinder { updateItems ->
                    return morpheusContext.async.virtualImage.location.listById(updateItems.collect { it.existingItem.id } as List<Long>)
                }.start()
            } else {
                log.error("Error not getting the listResults")
            }
        } catch (e) {
            log.error("ImagesSync error: ${e}", e)
        }
    }

    /**
     * Adds missing virtual image locations for the provided collection of cloud items.
     *
     * @param objList A collection of VM.Record objects representing cloud items.
     */
    def addMissingVirtualImageLocations(Collection<VM.Record> objList) {
        log.debug "addMissingVirtualImageLocations: ${objList?.size()}"
        try {
            def names = objList.collect { it.nameLabel }?.unique()
            def allowedImageTypes = ['vhd', 'vmdk']

            def objListIds = objList.collect { it.uuid }?.unique()

            def uniqueIds = [] as Set
            Observable domainRecords = morpheusContext.async.virtualImage.listIdentityProjections(
				new DataQuery().withFilters(
						new DataOrFilter(
								new DataAndFilter(
										new DataFilter('refType', 'ComputeZone'),
										new DataFilter('refId', cloud.id)
								),
								new DataAndFilter(
										new DataFilter('accounts.id', cloud.account.id),
										new DataFilter('category', "xenserver.image.${cloud.id}"),
										new DataFilter('uniqueId', 'in', objListIds)
								)
						)
				)
			).filter { VirtualImageIdentityProjection proj ->
                if(objListIds.contains(proj.externalId)){
                    return true
                }

                def include = proj.imageType in allowedImageTypes && proj.name in names && (proj.systemImage || (!proj.ownerId || proj.ownerId == cloud.owner.id))
                if (include) {
                    def uniqueKey = "${proj.imageType.toString()}:${proj.name}".toString()
                    if (!uniqueIds.contains(uniqueKey)) {
                        uniqueIds << uniqueKey
                        return true
                    }
                }
                return false
            }

            SyncTask<VirtualImageIdentityProjection, VM.Record, VirtualImage> syncTask = new SyncTask<>(domainRecords, objList)
            syncTask.addMatchFunction { VirtualImageIdentityProjection domainObject, VM.Record cloudItem ->
                domainObject.name == cloudItem.nameLabel
            }.onAdd { itemsToAdd ->
                addMissingVirtualImages(itemsToAdd)
            }.onUpdate { List<SyncTask.UpdateItem<VirtualImage, VM.Record>> updateItems ->
                // Found the VirtualImage for this location.. just need to create the location
                addMissingVirtualImageLocationsForImages(updateItems)
            }.withLoadObjectDetailsFromFinder { updateItems ->
                return morpheusContext.async.virtualImage.listById(updateItems?.collect { it.existingItem.id } as List<Long>)
            }.start()
        } catch (e){
            log.error("Error in addMissingVirtualImageLocations: ${e}", e)
        }
    }

    /**
     * Adds missing virtual images for the provided list of cloud items.
     *
     * @param addList A collection of VM.Record objects representing cloud items to be added as virtual images.
     */
    private addMissingVirtualImages(Collection<VM.Record> addList) {
        log.debug "addMissingVirtualImages ${addList?.size()}"
        try{
            def adds = []
            def addExternalIds = []
            addList?.each {
                def imageConfig = buildVirtualImageConfig(it)
                def add = new VirtualImage(imageConfig)
                def locationConfig = buildLocationConfig(add)
                VirtualImageLocation location = new VirtualImageLocation(locationConfig)
                add.imageLocations = [location]
                addExternalIds << add.externalId
                adds << add
            }

            // Create images
            log.debug "About to create ${adds.size()} virtualImages"
            morpheusContext.async.virtualImage.create(adds, cloud).blockingGet()
        } catch (e){
            log.error("Error in addMissingVirtualImages: ${e}", e)
        }

    }

    /**
     * Adds missing virtual image locations for the provided list of update items.
     *
     * @param addItems A list of SyncTask.UpdateItem objects containing virtual image and cloud item records.
     */
    private addMissingVirtualImageLocationsForImages(List<SyncTask.UpdateItem<VirtualImage, VM.Record>> addItems) {
        log.debug "addMissingVirtualImageLocationsForImages ${addItems?.size()}"
        try{
            def locationAdds = []
            addItems?.each { add ->
                VirtualImage virtualImage = add.existingItem
                def locationConfig = buildLocationConfig(virtualImage)
                VirtualImageLocation location = new VirtualImageLocation(locationConfig)
                locationAdds << location
            }

            if(locationAdds) {
                log.debug "About to create ${locationAdds.size()} locations"
                morpheusContext.async.virtualImage.location.create(locationAdds, cloud).blockingGet()
            }
        } catch (e){
            log.error("Error in addMissingVirtualImageLocationsForImages: ${e}", e)
        }
    }

    /**
     * Builds the configuration map for a virtual image based on the provided cloud item (VM.Record).
     *
     * @param cloudItem The cloud item (VM.Record) to build the virtual image configuration for.
     * @return A Map containing the configuration properties for the virtual image.
     */
    private buildVirtualImageConfig(VM.Record cloudItem) {

        def imageConfig = [
                account     : cloud.account,
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

        return imageConfig
    }

    /**
     * Builds the configuration map for a virtual image location based on the provided virtual image.
     *
     * @param image The virtual image for which the location configuration is to be built.
     * @return A Map containing the configuration properties for the virtual image location.
     */
    private Map buildLocationConfig(VirtualImage image) {
        return [
                virtualImage: image,
                code        : "xenserver.image.${cloud.id}.${image.externalId}",
                internalId  : image.externalId,
                externalId  : image.externalId,
                imageName   : image.name
        ]
    }

    /**
     * Updates matched virtual image locations based on the provided list of update items.
     *
     * @param updateList A list of SyncTask.UpdateItem objects containing virtual image location and cloud item records.
     */
    private updateMatchedVirtualImageLocations(List<SyncTask.UpdateItem<VirtualImageLocation, VM.Record>> updateList) {
        log.debug "updateMatchedVirtualImageLocations: ${updateList?.size()}"

        try{
            List<VirtualImageLocation> existingLocations = updateList?.collect { it.existingItem }

            def imageIds = updateList?.findAll{ it.existingItem.virtualImage?.id }?.collect{ it.existingItem.virtualImage.id }
            def externalIds = updateList?.findAll{ it.existingItem.externalId }?.collect{ it.existingItem.externalId }
            List<VirtualImage> existingItems = []
            if(imageIds && externalIds) {
                def tmpImgProjs = morpheusContext.async.virtualImage.listIdentityProjections(cloud.id).filter { img ->
                    img.id in imageIds || (!img.systemImage && img.externalId != null && img.externalId in externalIds)
                }.toList().blockingGet()
                if(tmpImgProjs) {
                    existingItems = morpheusContext.async.virtualImage.listById(tmpImgProjs.collect { it.id }).filter { img ->
                        img.id in imageIds || img.imageLocations.size() == 0
                    }.toList().blockingGet()
                }
            } else if(imageIds) {
                existingItems = morpheusContext.async.virtualImage.listById(imageIds).toList().blockingGet()
            }

            List<VirtualImageLocation> locationsToCreate = []
            List<VirtualImageLocation> locationsToUpdate = []
            List<VirtualImage> imagesToUpdate = []

            //updates
            updateList?.each { update ->
                def cloudItem = update.masterItem
                def virtualImageConfig = buildVirtualImageConfig(cloudItem)
                VirtualImageLocation imageLocation = existingLocations?.find { it.id == update.existingItem.id }
                if(imageLocation) {
                    def save = false
                    def saveImage = false
                    def image = existingItems.find {it.id == imageLocation.virtualImage.id}
                    if(image) {
                        if (imageLocation.uuid != virtualImageConfig.externalId) {
                            imageLocation.uuid = virtualImageConfig.externalId
                            save = true
                        }
                        if (image.refId != virtualImageConfig.refId) {
                            image.refId = virtualImageConfig.refId
                            saveImage = true
                        }

                        if (save) {
                            locationsToUpdate << imageLocation
                        }
                        if (saveImage) {
                            imagesToUpdate << image
                        }
                    }
                } else {
                    VirtualImage image = existingItems?.find { it.externalId == virtualImageConfig.externalId || it.name == virtualImageConfig.name }
                    if(image) {
                        //if we matched by virtual image and not a location record we need to create that location record
                        def addLocation = new VirtualImageLocation(buildLocationConfig(image))
                        locationsToCreate << addLocation
                        image.deleted = false
                        image.setPublic(false)
                        imagesToUpdate << image
                    }
                }

            }

            if(locationsToCreate.size() > 0 ) {
                morpheusContext.async.virtualImage.location.create(locationsToCreate, cloud).blockingGet()
            }
            if(locationsToUpdate.size() > 0 ) {
                morpheusContext.async.virtualImage.location.save(locationsToUpdate, cloud).blockingGet()
            }
            if(imagesToUpdate.size() > 0 ) {
                morpheusContext.async.virtualImage.save(imagesToUpdate, cloud).blockingGet()
            }
        } catch (e){
            log.error("Error in addMissingVirtualImageLocationsForImages: ${e}", e)
        }
    }

    /**
     * Removes missing virtual image locations from the Morpheus context based on the provided list of identity projections.
     *
     * @param removeList A list of VirtualImageLocationIdentityProjection objects representing the virtual image locations to be removed.
     */
    private removeMissingVirtualImageLocations(List<VirtualImageLocationIdentityProjection> removeList) {
        log.debug ("removeMissingVirtualImageLocations: ${cloud} ${removeList.size()}")
        morpheusContext.async.virtualImage.location.remove(removeList).blockingGet()
    }
}
