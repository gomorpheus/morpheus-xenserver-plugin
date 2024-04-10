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

//                Observable<VirtualImageIdentityProjection> existingVirtualImages = morpheusContext.async.virtualImage.listIdentityProjections(
//                        new DataQuery()
//                                .withFilter('accounts.id', cloud.account.id)
//                                .withFilter('category', "xenserver.image.${cloud.id}")
//                )
//                log.info("RAZI :: existingRecords: ${existingVirtualImages.toList().blockingGet()}")

                Observable<VirtualImageLocationIdentityProjection> domainRecords = morpheusContext.async.virtualImage.location.listIdentityProjections(cloud.id)
                SyncTask<VirtualImageLocationIdentityProjection, VM.Record, VirtualImageLocation> syncTask = new SyncTask<>(domainRecords, cloudItems as Collection<VM.Record>)
                syncTask.addMatchFunction {existingItem, cloudItem ->
                    log.info("RAZI :: existingItem.externalId: ${existingItem.externalId}")
                    log.info("RAZI :: cloudItem.uuid: ${cloudItem.uuid}")
                    existingItem.externalId == cloudItem.uuid
                }.onDelete { removeItems ->
                    removeMissingVirtualImageLocations(removeItems)
                }.onUpdate {updateItems ->
                    updateMatchedVirtualImageLocations(updateItems)
                }.onAdd { itemsToAdd ->
                    addMissingVirtualImageLocations(itemsToAdd, existingVirtualImages)
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

    def addMissingVirtualImageLocations(Collection<VM.Record> objList, Observable<VirtualImageIdentityProjection> existingVirtualImages) {
        log.debug "addMissingVirtualImageLocations: ${objList?.size()}"
        try {
            def names = objList.collect { it.nameLabel }?.unique()
//            List<VirtualImageIdentityProjection> existingItems = []
            def allowedImageTypes = ['vhd', 'vmdk']

//            Observable<VirtualImageIdentityProjection> existingVirtualImages = morpheusContext.async.virtualImage.listIdentityProjections(
//                    new DataQuery()
//                            .withFilter('accounts.id', cloud.account.id)
//                            .withFilter('category', "xenserver.image.${cloud.id}")
//            )
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
//                            new DataFilter('uniqueId', 'in', objListIds)
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
//        }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map>> updateItems ->
//            Map<Long, SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it] }
//            morpheusContext.virtualImage.listById(updateItems?.collect { it.existingItem.id }).map { VirtualImage virtualImage ->
//                SyncTask.UpdateItemDto<VirtualImageIdentityProjection, Map> matchItem = updateItemMap[virtualImage.id]
//                return new SyncTask.UpdateItem<VirtualImage, Map>(existingItem: virtualImage, masterItem: matchItem.masterItem)
//            }
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

    private addMissingVirtualImages(Collection<VM.Record> addList) {
        log.debug "addMissingVirtualImages ${addList?.size()}"
//        WindowsNativeDispatcher.Account account = cloud.account
//        def regionCode = cloud.regionCode
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

            // Create em all!
            log.debug "About to create ${adds.size()} virtualImages"
            morpheusContext.async.virtualImage.create(adds, cloud).blockingGet()
        } catch (e){
            log.error("Error in addMissingVirtualImages: ${e}", e)
        }

    }

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

    private buildVirtualImageConfig(VM.Record cloudItem) {
//        Account account = cloud.account
//        def regionCode = cloud.regionCode

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
     * Adds missing virtual images to the Morpheus context based on the provided list of VM records.
     *
     * @param addList A collection of VM records representing the virtual images to be added.
     */
//    private addMissingVirtualImages(Collection<VM.Record> addList) {
//        def imageAdds = []
//        try {
//            addList?.each { cloudItem ->
//                def imageConfig = [
//                        category    : "xenserver.image.${cloud.id}",
//                        name        : cloudItem.nameLabel,
//                        owner       : cloud.owner,
//                        description : cloudItem.nameDescription,
//                        imageType   : 'xen',
//                        code        : "xenserver.image.${cloud.id}.${cloudItem.uuid}",
//                        uniqueId    : cloudItem.uuid,
//                        status      : 'Active',
//                        externalId  : cloudItem.uuid,
//                        refType     : 'ComputeZone',
//                        refId       : "${cloud.id}"
//                ]
//                VirtualImage virtualImage = new VirtualImage(imageConfig)
//                virtualImage.account
//                def locationProps = [
//                        virtualImage: virtualImage,
//                        code        : "xenserver.image.${cloud.id}.${cloudItem.uuid}",
//                        internalId  : virtualImage.externalId,
//                        externalId  : virtualImage.externalId,
//                        imageName   : virtualImage.name
//                ]
//                VirtualImageLocation virtualImageLocation = new VirtualImageLocation(locationProps)
//                virtualImage.imageLocations = [virtualImageLocation]
//                imageAdds << virtualImage
//            }
//            //create images
//            morpheusContext.async.virtualImage.create(imageAdds, cloud).blockingGet()
//        } catch (e) {
//            log.error("Error in adding Image sync: ${e}", e)
//        }
//    }

    /**
     * Updates matched virtual images in the Morpheus context based on the provided list of update items.
     *
     * @param updateList A list of SyncTask.UpdateItem objects containing master and existing virtual image records.
     */
//    private void updateMatchedImages(List<SyncTask.UpdateItem<VirtualImage, VM.Record>> updateList) {
//        try {
//            log.debug("updateMatchedImages: ${updateList?.size()}")
//            List<VirtualImage> imagesToUpdate = []
//            updateList.each { it ->
//                def masterItem = it.masterItem
//                def existingItem = it.existingItem
//                def doSave = false
//
//                if (existingItem.uniqueId != masterItem.uuid) {
//                    existingItem.uniqueId = masterItem.uuid
//                    doSave = true
//                }
//
//                if (doSave) {
//                    imagesToUpdate << existingItem
//                }
//            }
//
//            log.debug("Have ${imagesToUpdate?.size()} to update")
//            morpheusContext.async.virtualImage.save(imagesToUpdate, cloud).blockingGet()
//        } catch (e) {
//            log.error("Error in updateMatchedImages method: ${e}", e)
//        }
//    }

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
    //                        if (image && (image.refId == imageLocation.refId.toString())) {
    //                            image.refId = virtualImageConfig.refId
    //                            imagesToUpdate << image
    //                            saveImage = true
    //                        }
                            save = true
                        }
    //                    if (imageLocation.imageRegion != virtualImageConfig.imageRegion) {
    //                        imageLocation.imageRegion = virtualImageConfig.imageRegion
    //                        save = true
    //                    }
                        if (image.refId != virtualImageConfig.refId) {
                            image.refId = virtualImageConfig.refId
                            saveImage = true
                        }
    //                    if (image.imageRegion != virtualImageConfig.imageRegion) {
    //                        image.imageRegion = virtualImageConfig.imageRegion
    //                        saveImage = true
    //                    }
    //                    if (image.minDisk != virtualImageConfig.minDisk) {
    //                        image.minDisk = virtualImageConfig.minDisk
    //                        saveImage = true
    //                    }
    //                    if (image.bucketId != virtualImageConfig.bucketId) {
    //                        image.bucketId = virtualImageConfig.bucketId
    //                        saveImage = true
    //                    }
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

    private removeMissingVirtualImageLocations(List<VirtualImageLocationIdentityProjection> removeList) {
        log.debug ("removeMissingVirtualImageLocations: ${cloud} ${removeList.size()}")
        morpheusContext.async.virtualImage.location.remove(removeList).blockingGet()
    }
}
