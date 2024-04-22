package com.morpheusdata.xen

import com.bertramlabs.plugins.karman.CloudFile
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.BackupResult
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
		ServiceResponse rtn = new ServiceResponse(true, null, [:], null)
		def validationOpts = [:]
		try{
			if (opts.containsKey('imageId') || opts?.config?.containsKey('imageId'))
				validationOpts += [imageId: opts?.config?.imageId ?: opts?.imageId]
			if(opts.networkInterfaces) {
				validationOpts.networkInterfaces = opts.networkInterfaces
			}
			log.info("Rahul2:: validateWorkload:validationOpts: ${validationOpts}")
			def validationResults = XenComputeUtility.validateServerConfig(validationOpts)
			log.info("Rahul2:: validateWorkload:validationResults: ${validationResults}")
			/*if(!validationResults.success) {
				rtn.success = false
				rtn.errors += validationResults.errors
			}*/
			if(!validationResults.success) {
				validationResults.errors?.each { it ->
					rtn.addError(it.field, it.msg)
				}
			}
		} catch(e) {
			log.error("validate container error: ${e}", e)
		}
		return rtn
		//return ServiceResponse.success()
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
		log.info("Ray:: runworkload: ========================================================")
		log.info("Ray:: runworkload: opts: ${opts}")
		log.info("Ray:: runworkload: ========================================================")
		log.info("Ray:: runworkload: opts.backupSetId: ${opts.backupSetId}")

		ProvisionResponse provisionResponse = new ProvisionResponse(success: true)
		ComputeServer server = workload.server
		log.info("Ray:: runworkload: server?.name: ${server?.name}")
		Cloud cloud = server.cloud
		log.info("Ray:: runworkload: cloud?.id: ${cloud?.id}")
		def virtualImage
		def imageId
		def imageFormat = 'vhd'
		def sourceVmId

		try {
			def containerConfig = workload.getConfigMap()
			log.info("Ray:: runworkload: containerConfig: ${containerConfig}")
			def rootVolume = server.volumes?.find{it.rootVolume == true}
			log.info("Ray:: runworkload: rootVolume?.externalId: ${rootVolume?.externalId}")
			def networkId = containerConfig.networkId
			log.info("Ray:: runworkload: rnetworkId: ${networkId}")
			def network = context.async.network.get(networkId).blockingGet()
			log.info("Ray:: runworkload: network: ${network}")
			log.info("Ray:: runworkload: network?.externalId: ${network?.externalId}")
			log.info("Ray:: runworkload: opts.cloneContainerId: ${opts.cloneContainerId}")
			def sourceWorkload = context.async.workload.get(opts.cloneContainerId).blockingGet()
			log.info("Ray:: runworkload: sourceWorkload: ${sourceWorkload}")
			log.info("Ray:: runworkload: sourceWorkload?.externalId: ${sourceWorkload?.externalId}")
			def cloneContainer = opts.cloneContainerId ? sourceWorkload : null
			log.info("Ray:: runworkload: cloneContainer: ${cloneContainer}")
			log.info("Ray:: runworkload: cloneContainer?.externalId: ${cloneContainer?.externalId}")
			def morphDataStores = context.async.cloud.datastore.listById([containerConfig.datastoreId?.toLong()])
					.toMap {it.id.toLong()}.blockingGet()
			log.info("Ray:: runworkload: morphDataStores: ${morphDataStores}")
			log.info("Ray:: runworkload: containerConfig.datastoreId: ${containerConfig.datastoreId}")
			log.info("Ray:: runworkload: rootVolume?.datastore: ${rootVolume?.datastore}")
			def datastore = rootVolume?.datastore ?: morphDataStores[containerConfig.datastoreId?.toLong()]
			log.info("Ray:: runworkload: datastore: ${datastore}")
			log.info("Ray:: runworkload: datastore?.name: ${datastore?.name}")

			log.info("Ray:: runworkload: containerConfig.imageId: ${containerConfig.imageId}")
			log.info("Ray:: runworkload: containerConfig.template: ${containerConfig.template}")
			log.info("Ray:: runworkload: server.sourceImage: ${server.sourceImage}")
			log.info("Ray:: runworkload: server.sourceImage?.id: ${server.sourceImage?.id}")
			if(containerConfig.imageId || containerConfig.template || server.sourceImage?.id) {
				def virtualImageId = (containerConfig.imageId?.toLong() ?: containerConfig.template?.toLong() ?: server.sourceImage.id)
				log.info("Ray:: runworkload: virtualImageId: ${virtualImageId}")
				virtualImage = context.async.virtualImage.get(virtualImageId).blockingGet()
				log.info("Ray:: runworkload: virtualImage: ${virtualImage}")
				log.info("Ray:: runworkload: virtualImage?.externalId: ${virtualImage?.externalId}")
				imageId = virtualImage?.externalId
				log.info("Ray:: runworkload: imageId: ${imageId}")
				if(!imageId) { //If its userUploaded and still needs uploaded
					//TODO: We need to upload ovg/vmdk stuff here
					log.info("Ray:: runworkload: server.interfaces: ${server.interfaces}")
					def primaryNetwork = server.interfaces?.find{it.network}?.network
					log.info("Ray:: runworkload: primaryNetwork: ${primaryNetwork}")
					log.info("Ray:: runworkload: primaryNetwork.name: ${primaryNetwork.name}")
					def cloudFiles = context.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
					log.info("Ray:: runworkload: cloudFiles: ${cloudFiles}")
					log.info("Ray:: runworkload: cloudFiles.size(): ${cloudFiles.size()}")
					def imageFile = cloudFiles?.find{cloudFile -> cloudFile.name.toLowerCase().indexOf('.' + imageFormat) > -1}
					log.info("Ray:: runworkload: imageFile: ${imageFile}")
					log.info("Ray:: runworkload: imageFile.name: ${imageFile.name}")
					def containerImage =
							[
									name			: virtualImage.name,
									imageSrc		: imageFile?.getURL(),
									minDisk			: virtualImage.minDisk ?: 5,
									minRam			: virtualImage.minRam ?: (512* ComputeUtility.ONE_MEGABYTE),
									tags			: 'morpheus, ubuntu',
									imageType		: 'disk_image',
									containerType 	: 'vhd',
									cloudFiles		: cloudFiles,
									imageFile		: imageFile,
									imageSize		: imageFile.contentLength
							]
					log.info("Ray:: runworkload: containerImage: ${containerImage}")

					def imageConfig =
							[
									zone		: cloud,//rahul: image 2 times
									image		: containerImage,
									//cachePath	: virtualImageService.getLocalCachePath(),
									name		: virtualImage.name,
									datastore	: datastore,
									network		: primaryNetwork,
									osTypeCode	: virtualImage?.osType?.code,
									image		: containerImage //rahul: image 2 times
							]
					imageConfig.authConfig = plugin.getAuthConfig(cloud)
					log.info("Ray:: runworkload: imageConfig: ${imageConfig}")
					def imageResults = XenComputeUtility.insertTemplate(imageConfig)
					log.info("Ray:: runworkload: imageResults: ${imageResults}")
					log.info("Ray:: runworkload: imageResults.success: ${imageResults.success}")
					log.info("Ray:: runworkload: imageResults.imageId: ${imageResults.imageId}")
					if(imageResults.success == true) {
						imageId = imageResults.imageId
					}
				}
			}
			log.info("Ray:: runworkload: opts.backupSetId1: ${opts.backupSetId}")
			log.info("Ray:: runworkload: opts.cloneContainerId1: ${opts.cloneContainerId}")
			if(opts.backupSetId){
				//if this is a clone or restore, use the snapshot id as the image
				//rahul: need to backup provider logic
				//def snapshot = backupService.getSnapshotForBackupResult(opts.backupSetId, opts.cloneContainerId) // rahul: how to get the snapshot
				//def snapshots = new XenserverBackupTypeProvider(plugin, morpheus).getSnapshotsForBackupResult(opts.backupSetId, opts.cloneContainerId)
				def snapshot = plugin.morpheus.async.backup.backupResult.listByBackupSetIdAndContainerId(opts.backupSetId, opts.cloneContainerId).blockingFirst()
				log.info("Ray:: runworkload: snapshot: ${snapshot}")
				log.info("Ray:: runworkload: snapshot?.snapshotId: ${snapshot?.snapshotId}")
				def snapshotId = snapshot?.snapshotId
				log.info("Ray:: runworkload: snapshotId: ${snapshotId}")
				log.info("Ray:: runworkload: snapshot?.dump(): ${snapshot?.dump()}")
				//sourceVmId = snapshot?.vmId
				if(snapshotId) {
					log.info("creating server from snapshot: ${snapshotId}")
					imageId = snapshotId
				}
				log.info("Ray:: runworkload: network: ${network}")
				log.info("Ray:: runworkload: network?.configMap: ${network?.configMap}")
				if(!network && (cloneContainer /*|| snapshot.containerConfig*/)) { // rahul snapshot.configMap
					def cloneContainerConfig = cloneContainer?.getConfigProperty("") ?: snapshot.configMap // rahul:: what need to be used in place of -> cloneContainer?.getConfigProperties()
					log.info("Ray:: runworkload: cloneContainerConfig: ${cloneContainerConfig}")
					//log.info("Ray:: runworkload: cloneContainerConfig: ${cloneContainerConfig}")
					//networkId = cloneContainerConfig.networkId
					if (networkId) {
						containerConfig.networkId = networkId
						//workload.setConfigProperties(containerConfig)
						//container.save(flush: true)
						//workload = context.async.workload.save(workload)
						network = context.async.network.get(networkId).blockingGet()
					}
				}
			}

			/*if(imageId) {
				opts.installAgent = virtualImage ? virtualImage.installAgent : true
				//user config
				def createdBy = getInstanceCreateUser(container.instance) // Rahul: how to get created by
				def userGroups = workload.instance.userGroups?.toList() ?: []
				if (workload.instance.userGroup && userGroups.contains(workload.instance.userGroup) == false) {
					userGroups << workload.instance.userGroup
				}
				opts.userConfig = userGroupService.buildContainerUserGroups(opts.account, virtualImage, userGroups,
						createdBy, opts)
				opts.server.sshUsername = opts.userConfig.sshUsername
				opts.server.sshPassword = opts.userConfig.sshPassword
				opts.server.sourceImage = virtualImage
				opts.server.serverOs = opts.server.serverOs ?: virtualImage.osType
				opts.server.osType = (virtualImage.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform
				def newType = findVmNodeZoneType(opts.server.zone.zoneType, opts.server.osType)
				if(newType && opts.server.computeServerType != newType)
					opts.server.computeServerType = newType
				opts.server.save(flush:true)
				def maxMemory = container.maxMemory ?: container.instance.plan.maxMemory
				def maxCores = container.maxCores ?: container.instance.plan.maxCores
				def maxStorage = getContainerRootSize(container)
				def dataDisks = getContainerDataDiskList(container)
				def createOpts = [account:opts.account, name:opts.server.name, maxMemory:maxMemory, maxStorage:maxStorage, maxCpu:maxCores,
								  imageId:imageId, server:opts.server, zone:opts.zone, externalId:opts.server.externalId,
								  networkType:config.networkType, datastore:datastore, network:network, dataDisks:dataDisks, platform:opts.server.osType]
				createOpts.hostname = opts.server.getExternalHostname()
				createOpts.domainName = opts.server.getExternalDomain()
				createOpts.fqdn = createOpts.hostname
				if(createOpts.domainName) {
					createOpts.fqdn += '.' + createOpts.domainName
				}

				createOpts.networkConfig = opts.networkConfig
				//cloud init config
				createOpts.isoDatastore = XenComputeUtility.findIsoDatastore(opts)
				if(opts.zone.distributedWorkers) {
					//we need to use a worker upload
					opts.worker = opts.zone.distributedWorkers.first()
					opts.workerCommandService = commandService
					def applianceServerUrl = applianceService.getApplianceUrl(opts.server.zone)
					opts.cloudFileUrl = applianceServerUrl + (applianceServerUrl.endsWith('/') ? '' : '/') + 'api/cloud-config/' + opts.server.apiKey

				}
				if(virtualImage?.isCloudInit) {
					def cloudConfigOpts = buildCloudConfigOpts(opts.zone, opts.server, !opts.noAgent, [doPing:true, hostname:opts.server.getExternalHostname(),
																									   hosts:opts.server.getExternalHostname(), disableCloudInit:true, timezone: containerConfig.timezone])
					opts.installAgent = opts.installAgent && (cloudConfigOpts.installAgent != true) && !opts.noAgent
					morpheusComputeService.buildCloudNetworkConfig(createOpts.platform, virtualImage, cloudConfigOpts, createOpts.networkConfig)
					createOpts.cloudConfigUser = morpheusComputeService.buildCloudUserData(opts.server.osType, opts.userConfig, cloudConfigOpts)
					createOpts.cloudConfigMeta = morpheusComputeService.buildCloudMetaData(opts.server.osType, "morpheus-${opts.server.id}", opts.server.getExternalHostname(), cloudConfigOpts)
					createOpts.cloudConfigNetwork = morpheusComputeService.buildCloudNetworkData(createOpts.platform, cloudConfigOpts)
					opts.server.cloudConfigUser = createOpts.cloudConfigUser
					opts.server.cloudConfigMeta = createOpts.cloudConfigMeta
					opts.server.cloudConfigNetwork = createOpts.cloudConfigNetwork
					opts.server.save(flush:true)
					def cloudFileDiskName = 'morpheus_server_' + opts.server.id + '.iso'
					createOpts.cloudConfigFile = cloudFileDiskName
				} else if(virtualImage?.isSysprep) {
					def cloudConfigOpts = buildCloudConfigOpts(opts.zone, opts.server, !opts.noAgent, [doPing:true, hostname:opts.server.getExternalHostname(),
																									   hosts:opts.server.getExternalHostname(), disableCloudInit:true, timezone: containerConfig.timezone])
					opts.installAgent = opts.installAgent && (cloudConfigOpts.installAgent != true) && !opts.noAgent
					morpheusComputeService.buildCloudNetworkConfig(createOpts.platform, virtualImage, cloudConfigOpts, createOpts.networkConfig)
					createOpts.cloudConfigUser = morpheusComputeService.buildCloudUserData(opts.server.osType, opts.userConfig, cloudConfigOpts)
					opts.server.cloudConfigUser = createOpts.cloudConfigUser
					opts.server.save(flush:true)
					def cloudFileDiskName = 'morpheus_server_' + opts.server.id + '.iso'
					createOpts.cloudConfigFile = cloudFileDiskName
				} else {
					opts.createUserList = opts.userConfig.createUsers
				}
				log.info("Creating XenServer VM: [Zone: {}, VM Name: {}, account: {}",createOpts.zone.name,createOpts.name,createOpts.account.name)
				log.debug("Creating VM on Xen Server Additional Details: ${createOpts}")
				def createResults
				if(sourceVmId) {
					createOpts.sourceVmId = sourceVmId
					createResults = XenComputeUtility.cloneServer(createOpts)
				} else {
					createResults = XenComputeUtility.createServer(createOpts)
				}
				log.info("Create XenServer VM Results: ${createResults}")
				if(createResults.success == true && createResults.vmId) {
					opts.server.externalId = createResults.vmId
					opts.server.save(flush:true)
					setVolumeInfo(opts.server.volumes, createResults.volumes, opts.zone)
					opts.server.save(flush:true)
					def startResults = XenComputeUtility.startVm(opts, opts.server.externalId)
					log.debug("start: ${startResults.success}")
					if(startResults.success == true) {
						if(startResults.error == true) {
							opts.server.statusMessage = 'Failed to start server'
							//ouch - delet it?
						} else {
							//good to go
							def serverDetail = checkServerReady([zone:opts.zone, externalId:opts.server.externalId])
							log.debug("serverDetail: ${serverDetail}")
							if(serverDetail.success == true) {
								def privateIp = serverDetail.ipAddress
								def publicIp = serverDetail.ipAddress
								//def poolId = createResults.results.server?.networkPoolId
								//save off interfaces
								//opts.network = applyComputeServerNetworkIp(opts.server, privateIp, publicIp, hostname, poolId, 0)
								serverDetail.ipAddresses.each { interfaceName, data ->
									ComputeServerInterface netInterface = opts.server.interfaces.find{it.name == interfaceName}
									if(netInterface) {
										if(data.ipAddress) {
											def address = new NetAddress(address: data.ipAddress, type: NetAddress.AddressType.IPV4)
											if(!address.validate()){
												log.debug("NetAddress Errors: ${address.errors}")
											}
											netInterface.addToAddresses(address)
										}
										if(data.ipv6Address) {
											def address = new NetAddress(address: data.ipv6Address, type: NetAddress.AddressType.IPV6)
											if(!address.validate()){
												log.debug("NetAddress Errors: ${address.errors}")
											}
											netInterface.addToAddresses(address)
										}
										netInterface.publicIpAddress = data.ipAddress
										netInterface.publicIpv6Address = data.ipv6Address
										netInterface.save(flush:true)
									}
								}
								if(privateIp) {
									def newInterface = false
									opts.server.internalIp = privateIp
									opts.server.externalIp = publicIp
									opts.server.sshHost = privateIp
								}
								//update external info
								setNetworkInfo(opts.server.interfaces, serverDetail.networks)
								opts.server.osDevice = '/dev/vda'
								opts.server.dataDevice = '/dev/vda'
								opts.server.lvmEnabled = false
								opts.server.sshHost = privateIp ?: publicIp
								opts.server.managed = true
								opts.server.save(flush:true, failOnError:true)
								opts.server.capacityInfo = new ComputeCapacityInfo(server:opts.server, maxCores:1,
										maxMemory:container.getConfigProperty('maxMemory').toLong(), maxStorage:container.getConfigProperty('maxStorage').toLong())
								opts.server.capacityInfo.save()
								opts.server.status = 'provisioned'
								opts.server.save(flush:true)
								instanceService.updateInstance(container.instance)
								rtn.success = true
								//ok - done - delete cloud disk
								//XenComputeUtility.deleteImage(opts, cloudFileResults.imageId)
							} else {
								opts.server.statusMessage = 'Failed to load server details'
							}
						}
					} else {
						opts.server.statusMessage = 'Failed to start server'
					}
				} else {
					setProvisionFailed(server, container, createResults.msg ?: 'An unknown error occurred while making an API request to Xen.')
					rtn.msg = createResults.msg ?: 'An unknown error occurred while making an API request to Xen.'
				}
			} else {
				opts.server.statusMessage = 'Image not found'
			}*/

		} catch (e) {
			log.error("initializeServer error:${e}", e)
			log.error("Ray:: XCU:runworkload: exception: ${e.getMessage()}")
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
