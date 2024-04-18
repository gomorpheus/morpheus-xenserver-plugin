package com.morpheusdata.xen

import com.bertramlabs.plugins.karman.CloudFile
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeTypeSet
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.Workload
import com.morpheusdata.model.WorkloadType
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.xen.util.XenComputeUtility
import groovy.util.logging.Slf4j

@Slf4j
class XenserverProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider {

	public static final String PROVIDER_NAME = 'XenServer'
	public static final String PROVIDER_CODE = 'xen'
	public static final String PROVISION_TYPE_CODE = 'xen'

	protected MorpheusContext context
	protected XenserverPlugin plugin

	public XenserverProvisionProvider(XenserverPlugin plugin, MorpheusContext ctx) {
		super()
		this.@context = ctx
		this.@plugin = plugin
	}

	/**
	 * This method is called before runWorkload and provides an opportunity to perform action or obtain configuration
	 * that will be needed in runWorkload. At the end of this method, if deploying a ComputeServer with a VirtualImage,
	 * the sourceImage on ComputeServer should be determined and saved.
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload. This will be passed along into runWorkload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<PrepareWorkloadResponse>(
			true, // successful
			'', // no message
			null, // no errors
			new PrepareWorkloadResponse(workload:workload) // adding the workload to the response for convenience
		)
		return resp
	}

	/**
	 * Some older clouds have a provision type code that is the exact same as the cloud code. This allows one to set it
	 * to match and in doing so the provider will be fetched via the cloud providers method.
	 * @return code for overriding the ProvisionType record code property
	 */
	@Override
	String getProvisionTypeCode() {
		return PROVISION_TYPE_CODE
	}

	/**
	 * Provide an icon to be displayed for ServicePlans, VM detail page, etc.
	 * where a circular icon is displayed
	 * @since 0.13.6
	 * @return Icon
	 */
	@Override
	Icon getCircularIcon() {
		// TODO: change icon paths to correct filenames once added to your project
		return new Icon(path:'provision-circular.svg', darkPath:'provision-circular-dark.svg')
	}

	/**
	 * Provides a Collection of OptionType inputs that need to be made available to various provisioning Wizards
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		Collection<OptionType> options = []
		// TODO: create some option types for provisioning and add them to collection
		return options
	}

	/**
	 * Provides a Collection of OptionType inputs for configuring node types
	 * @since 0.9.0
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getNodeOptionTypes() {
		Collection<OptionType> nodeOptions = []
		return nodeOptions
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for root StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		context.async.storageVolume.storageVolumeType.list(
				new DataQuery().withFilter("code", "standard")).toList().blockingGet()
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for data StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		context.async.storageVolume.storageVolumeType.list(
				new DataQuery().withFilter("code", "standard")).toList().blockingGet()
	}

	/**
	 * Provides a Collection of ${@link ServicePlan} related to this ProvisionProvider that can be seeded in.
	 * Some clouds do not use this as they may be synced in from the public cloud. This is more of a factor for
	 * On-Prem clouds that may wish to have some precanned plans provided for it.
	 * @return Collection of ServicePlan sizes that can be seeded in at plugin startup.
	 */
	@Override
	Collection<ServicePlan> getServicePlans() {
		Collection<ServicePlan> plans = []
		// TODO: create some service plans (sizing like cpus, memory, etc) and add to collection
		return plans
	}

	/**
	 * Validates the provided provisioning options of a workload. A return of success = false will halt the
	 * creation and display errors
	 * @param opts options
	 * @return Response from API. Errors should be returned in the errors Map with the key being the field name and the error
	 * message as the value.
	 */
	@Override
	ServiceResponse validateWorkload(Map opts) {
		log.debug("validateWorkload: ${opts}")
		log.info("Rahul:: validateWorkload:opts: ${opts}")
		/*ServiceResponse rtn = new ServiceResponse(true, null, [:], null)
		def validationOpts = [:]
		try{
			if (opts.containsKey('imageId') || opts?.config?.containsKey('imageId'))
				validationOpts += [imageId: opts?.config?.imageId ?: opts?.imageId]
			if(opts.networkInterfaces) {
				validationOpts.networkInterfaces = opts.networkInterfaces
			}
			def validationResults = XenComputeUtility.validateServerConfig(validationOpts)
				log.info("Rahul:: validateWorkload:validationResults: ${validationResults}")
			*//*if(!validationResults.success) {
				rtn.success = false
				rtn.errors += validationResults.errors
			}*//*
			if(!validationResults.success) {
				validationResults.errors?.each { it ->
					rtn.addError(it.field, it.msg)
				}
			}
		} catch(e) {
			log.error("validate container error: ${e}", e)
		}
		return rtn*/
		return ServiceResponse.success()
	}

	/**
	 * This method is a key entry point in provisioning a workload. This could be a vm, a container, or something else.
	 * Information associated with the passed Workload object is used to kick off the workload provision request
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug "runWorkload: ${workload} ${workloadRequest} ${opts}"
		log.info("Rahul:: runworkload: opts: ${opts}")
		ProvisionResponse provisionResponse = new ProvisionResponse(success: true)
		ComputeServer server = workload.server
		log.info("Rahul:: runworkload: server.name: ${server.name}")
		def containerConfig = workload.getConfigMap()
		log.info("Rahul:: runworkload: workload.getConfigMap(): ${workload.getConfigMap()}")
		log.info("Rahul:: runworkload: containerConfig: ${containerConfig}")
		Cloud cloud = server.cloud
		log.info("Rahul:: runworkload: cloud?.id: ${cloud?.id}")
		try{
			def virtualImage
			def imageId
			def imageFormat = 'vhd'
			def rootVolume = server.volumes?.find{it.rootVolume == true}
			log.info("Rahul:: runworkload: rootVolume: ${rootVolume}")
			log.info("Rahul:: runworkload: rootVolume?.name: ${rootVolume?.name}")
			def morphDatastore = context.async.cloud.datastore.listById([containerConfig.datastoreId?.toLong()]).firstOrError().blockingGet()
			log.info("Rahul:: runworkload: morphDatastore: ${morphDatastore}")
			log.info("Rahul:: runworkload: morphDatastore?.name: ${morphDatastore?.name}")
			log.info("Rahul:: runworkload: rootVolume?.datastore?.name ${rootVolume?.datastore?.name}")
			def datastore = rootVolume?.datastore ?: morphDatastore // rahul dataquery for datastore
			log.info("Rahul:: runworkload: datastore?.name ${datastore?.name}")
			def cloneContainer = opts.cloneContainerId
			WorkloadType containerType
			Long computeTypeSetId = server?.typeSet?.id
			log.info("Rahul:: runworkload: computeTypeSetId ${computeTypeSetId}")
			if(computeTypeSetId) {
				ComputeTypeSet computeTypeSet = context.services.computeTypeSet.get(computeTypeSetId)
				WorkloadType workloadType = computeTypeSet.getWorkloadType()
				if(workloadType) {
					containerType = context.services.containerType.get(workloadType.id)
				}
			}
			log.info("Rahul:: runworkload: containerType?.name ${containerType?.name}")
			log.info("Rahul:: runworkload: containerType?.virtualImage?.id ${containerType?.virtualImage?.id}")
			if(containerConfig.imageId || containerConfig.template || containerType?.virtualImage?.id) { //-- rahul
				def virtualImageId = (containerConfig.imageId?.toLong() ?: containerConfig.template?.toLong() ?: containerType?.virtualImage.id) // rahul
				log.info("Rahul:: runworkload: virtualImageId ${virtualImageId}")
				virtualImage = context.services.virtualImage.get(virtualImageId) // Rahul
				log.info("Rahul:: runworkload: virtualImage ${virtualImage}")
				log.info("Rahul:: runworkload: virtualImage?.externalId ${virtualImage?.externalId}")
				imageId = virtualImage.externalId
				log.info("Rahul:: runworkload: imageId ${imageId}")
				if(!imageId) { //If its userUploaded and still needs uploaded
					//TODO: We need to upload ovg/vmdk stuff here
					def primaryNetwork = server.interfaces?.find{it.network}?.network
					log.info("Rahul:: runworkload: primaryNetwork ${primaryNetwork}")
					log.info("Rahul:: runworkload: primaryNetwork?.name ${primaryNetwork?.name}")
					def cloudFiles = context.async.virtualImage.getVirtualImageFiles(virtualImage)
					log.info("Rahul:: runworkload: cloudFiles: ${cloudFiles}")
					log.debug("runContainer cloudFiles: {}", cloudFiles)
					CloudFile imageFile = cloudFiles?.find{ cloudFile -> cloudFile.name.toLowerCase().indexOf('.' + imageFormat) > -1}
					log.info("Rahul:: runworkload: imageFile: ${imageFile}")
					log.info("Rahul:: runworkload: imageFile:url: ${imageFile?.getURL()} -  ${imageFile?.name}")
					log.debug("runContainer imageFile: {}", imageFile)
					def containerImage =
							[
									name			: virtualImage.name,
									imageSrc		: imageFile?.getURL(),
									minDisk			: virtualImage.minDisk ?: 5,
									minRam			: virtualImage.minRam ?: (512* ComputeUtility.ONE_MEGABYTE),
									tags			: 'morpheus, ubuntu',
									imageType		: 'disk_image',
									containerType	: 'vhd',
									cloudFiles		: cloudFiles,
									imageFile		: imageFile,
									imageSize		: imageFile.contentLength
							]
					log.info("Rahul:: runworkload: containerImage: ${containerImage}")
					def imageConfig =
							[
									zone		: opts.cloud,
									image		: containerImage,
									//cachePath	: //virtualImage.imageLocations.//context.async.virtualImage.getLocation(),//virtualImage.getLocalCachePath(),
									name		: virtualImage.name,
									datastore	: datastore,
									network		: primaryNetwork,
									osTypeCode	: virtualImage?.osType?.code
							]
					imageConfig.authConfig = plugin.getAuthConfig(cloud)
					log.info("Rahul:: runworkload: imageConfig: ${imageConfig}")
					def imageResults = XenComputeUtility.insertTemplate(imageConfig)
					log.info("Rahul:: runworkload: imageResults: ${imageResults}")
					if(imageResults.success == true) {
						imageId = imageResults.imageId
					}
				} else {
					log.info("Rahul:: not going in image block")
				}

			}

		} catch(e) {
			log.error("Rahul:: runworkload: error ${e}", e)
		}



		// TODO: this is where you will implement the work to create the workload in your cloud environment
		return new ServiceResponse<ProvisionResponse>(
			true,
			null, // no message
			null, // no errors
			new ProvisionResponse(success:true)
		)
	}

	/**
	 * This method is called after successful completion of runWorkload and provides an opportunity to perform some final
	 * actions during the provisioning process. For example, ejected CDs, cleanup actions, etc
	 * @param workload the Workload object that has been provisioned
	 * @return Response from the API
	 */
	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		return ServiceResponse.success()
	}

	/**
	 * Issues the remote calls necessary top stop a workload element from running.
	 * @param workload the Workload we want to shut down
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopWorkload(Workload workload) {
		def rtn = [success: false, msg: null]
		try {
			if(workload.server?.externalId) {
				workload.userStatus = Workload.Status.stopped
				workload = context.async.workload.create(workload).blockingGet()
				Cloud cloud = workload.server.cloud
				def stopResults = XenComputeUtility.stopVm(plugin.getAuthConfig(cloud), workload.server.externalId)
				if(stopResults.success == true) {
					workload.status = Workload.Status.stopped
					workload = context.async.workload.create(workload).blockingGet()
					//stopContainerUsage(container, false)
					rtn.success = true
				}
			} else {
				rtn.success = true
				rtn.msg = 'vm not found'
			}
		} catch (e) {
			log.error("stopContainer error: ${e}", e)
			rtn.msg = e.message
		}
		return new ServiceResponse(rtn.success, rtn.msg, null, null)
	}

	/**
	 * Issues the remote calls necessary to start a workload element for running.
	 * @param workload the Workload we want to start up.
	 * @return Response from API
	 */
	@Override
	ServiceResponse startWorkload(Workload workload) {
		return ServiceResponse.success()
	}

	/**
	 * Issues the remote calls to restart a workload element. In some cases this is just a simple alias call to do a stop/start,
	 * however, in some cases cloud providers provide a direct restart call which may be preferred for speed.
	 * @param workload the Workload we want to restart.
	 * @return Response from API
	 */
	@Override
	ServiceResponse restartWorkload(Workload workload) {
		// Generally a call to stopWorkLoad() and then startWorkload()
		return ServiceResponse.success()
	}

	/**
	 * This is the key method called to destroy / remove a workload. This should make the remote calls necessary to remove any assets
	 * associated with the workload.
	 * @param workload to remove
	 * @param opts map of options
	 * @return Response from API
	 */
	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Method called after a successful call to runWorkload to obtain the details of the ComputeServer. Implementations
	 * should not return until the server is successfully created in the underlying cloud or the server fails to
	 * create.
	 * @param server to check status
	 * @return Response from API. The publicIp and privateIp set on the WorkloadResponse will be utilized to update the ComputeServer
	 */
	@Override
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
		return new ServiceResponse<ProvisionResponse>(true, null, null, new ProvisionResponse(success:true))
	}

	/**
	 * Method called before runWorkload to allow implementers to create resources required before runWorkload is called
	 * @param workload that will be provisioned
	 * @param opts additional options
	 * @return Response from API
	 */
	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Stop the server
	 * @param computeServer to stop
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	/**
	 * Start the server
	 * @param computeServer to start
	 * @return Response from API
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		return this.@context
	}

	/**
	 * Returns the instance of the Plugin class that this provider is loaded from
	 * @return Plugin class contains references to other providers
	 */
	@Override
	Plugin getPlugin() {
		return this.@plugin
	}

	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getCode() {
		return PROVIDER_CODE
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return PROVIDER_NAME
	}

	@Override
	Boolean hasNetworks() {
		true
	}

	@Override
	HostType getHostType() {
		HostType.vm
	}

	@Override
	String serverType() {
		return "vm"
	}

	@Override
	Boolean supportsCustomServicePlans() {
		return true;
	}

	@Override
	Boolean multiTenant() {
		return false
	}

	@Override
	Boolean aclEnabled() {
		return false
	}

	@Override
	Boolean customSupported() {
		return true;
	}

	@Override
	Boolean hasDatastores() {
		return true
	}

	@Override
	Boolean supportsAutoDatastore() {
		return false
	}

	@Override
	Boolean lvmSupported() {
		return true
	}

	@Override
	String getHostDiskMode() {
		return "lvm"
	}

	@Override
	String getDeployTargetService() {
		return "vmDeployTargetService"
	}

	@Override
	String getNodeFormat() {
		return "vm"
	}

	@Override
	Boolean hasSecurityGroups() {
		return false
	}

	@Override
	Boolean hasNodeTypes() {
		return true;
	}

	@Override
	Boolean createDefaultInstanceType() {
		return false
	}
}
