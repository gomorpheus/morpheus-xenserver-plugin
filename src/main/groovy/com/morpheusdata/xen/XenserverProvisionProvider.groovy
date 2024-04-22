package com.morpheusdata.xen

import com.morpheusdata.PrepareHostResponse
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.HostProvisionProvider
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ProxyConfiguration
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.xen.util.XenComputeUtility
import groovy.util.logging.Slf4j

@Slf4j
class XenserverProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider, HostProvisionProvider {

	public static final String PROVIDER_NAME = 'XenServer'
	public static final String PROVIDER_CODE = 'xenserver.provision'
	public static final String PROVISION_TYPE_CODE = 'xenserver'

	protected MorpheusContext context
//	protected Plugin plugin
	protected XenserverPlugin plugin

	public XenserverProvisionProvider(XenserverPlugin plugin, MorpheusContext context) {
		super()
		this.@context = context
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
	 * to match and in doing so the provider will be fetched via the cloud providers {CloudProvider#getDefaultProvisionTypeCode()} method.
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
		Collection<StorageVolumeType> volumeTypes = []
		// TODO: create some storage volume types and add to collection
		return volumeTypes
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for data StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		Collection<StorageVolumeType> dataVolTypes = []
		// TODO: create some data volume types and add to collection
		return dataVolTypes
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
		return ServiceResponse.success()
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
	ServiceResponse validateHost(ComputeServer server, Map opts) {
		log.debug("validateHost: ${server} ${opts}")
		def rtn =  ServiceResponse.success()
		try {
			def validationOpts = [
					networkId: opts?.config?.networkId,
					networkInterfaces: opts?.networkInterfaces
			]
			if (opts?.config?.templateTypeSelect == 'custom') {
				validationOpts += [imageId: opts?.config?.imageId]
			}
			if(opts?.config?.containsKey('nodeCount')){
				validationOpts += [nodeCount: opts.config.nodeCount]
			}
			def validationResults = XenComputeUtility.validateServerConfig(validationOpts)
			if(!validationResults.success) {
				rtn.success = false
				rtn.errors += validationResults.errors
			}
		} catch(e)  {
			log.error("error in validateHost: ${e}", e)
		}
		return rtn
	}

	@Override
	ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer computeServer, HostRequest hostRequest, Map map) {
		return null
	}

	@Override
	ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug("runHost: ${server} ${hostRequest} ${opts}")

			def rtn = [success:false]
		ProvisionResponse provisionResponse = new ProvisionResponse(success: true, installAgent: false)
			try {
				def layout = server?.layout
				def typeSet = server.typeSet
				def serverGroupType = layout?.groupType
				Boolean isKubernetes = serverGroupType?.providerType == 'kubernetes'
				def config = server.getConfigMap()
				log.info("RAZI :: config: ${config}")
				log.info("RAZI :: opts: ${opts}")
//				opts.zone = zoneService.loadFullZone(opts.zone ?: opts.server.zone)
				Cloud cloud = server.cloud
//				opts.account = opts.server.account
				Account account = server.account
				def zoneConfig = cloud.getConfigMap()
				def datastoreId = config.datastoreId
				def imageFormat = 'vhd'
				def imageType = config.templateTypeSelect ?: 'default'
				def imageId
				def virtualImage
//				def rootVolume = getServerRootDisk(opts.server)
				def rootVolume = server.volumes?.find{it.rootVolume == true}
//				def datastore = rootVolume?.datastore ?: Datastore.read(config.datastoreId?.toLong())
//				def datastoreId = rootVolume.datastore?.id
				def datastore = context.async.cloud.datastore.listById([datastoreId?.toLong()]).firstOrError().blockingGet()
				log.info("RAZI :: datastore: ${datastore}")

				log.debug("initializeServer datastore: ${datastore}")
				if(layout && typeSet) {
					virtualImage = typeSet.containerType.virtualImage
					imageId = virtualImage.externalId
				} else if(imageType == 'custom' && config.imageId) {
					def virtualImageId = config.imageId?.toLong()
//					virtualImage = VirtualImage.get(virtualImageId)
					virtualImage = server.sourceImage
					imageId = virtualImage.externalId
				} else {
//					virtualImage = VirtualImage.findByCode('xen.image.morpheus.ubuntu.20.04-v1.amd64') //better this later
//					virtualImage = context.async.virtualImage.getIdentityProperties()
//					virtualImage = context.services.virtualImage.list(new DataQuery().withFilter('code', 'xen.image.morpheus.ubuntu.20.04-v1.amd64'))
					virtualImage  = new VirtualImage(code: 'xen.image.morpheus.ubuntu.20.04-v1.amd64')
				}
				if(!imageId) { //If its userUploaded and still needs uploaded
//					def cloudFiles = virtualImageService.getVirtualImageFiles(virtualImage)
					def cloudFiles = context.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
					def imageFile = cloudFiles?.find{cloudFile -> cloudFile.name.toLowerCase().indexOf('.' + imageFormat) > -1}
					def primaryNetwork = server.interfaces?.find{it.network}?.network
					def containerImage = [name:virtualImage.name, imageSrc:imageFile?.getURL(), minDisk:virtualImage.minDisk ?: 5, minRam:virtualImage.minRam,
										  tags:'morpheus, ubuntu', imageType:'disk_image', containerType:'vhd', cloudFiles:cloudFiles, imageFile:imageFile]
					def imageResults = XenComputeUtility.insertTemplate([zone:opts.zone, image:containerImage, cachePath:"",//cachePath:virtualImageService.getLocalCachePath(),
																		 name:virtualImage.name, datastore:datastore, network:primaryNetwork, osTypeCode:virtualImage?.osType?.code])
					if(imageResults.success == true) {
						imageId = imageResults.imageId //uuid of the vm template
						//virtualImage.externalId = imageId - add image location object
						//virtualImage.save(flush:true)
					}
				}
//				if(imageId) {
//					setAgentInstallConfig(opts) //check with Dustin
//					def createdBy = getServerCreateUser(opts.server) //check with Dustin
//					def userGroups = server.userGroups?.toList() ?: []
//					if (opts.server.userGroup && userGroups.contains(opts.server.userGroup) == false) {
//						userGroups << opts.server.userGroup
//					}
//					opts.userConfig = userGroupService.buildContainerUserGroups(opts.account, virtualImage, userGroups,
//							createdBy, opts) //check with Dustin
//					server.sshUsername = opts.userConfig.sshUsername
//					server.sshPassword = opts.userConfig.sshPassword
//					server.sourceImage = virtualImage
//					def maxMemory = server.maxMemory ?: server.plan.maxMemory
//					def maxCpu = server.maxCpu ?: server.plan.maxCpu
//					def maxCores = server.maxCores ?: server.plan.maxCores
////					def maxStorage = getServerRootSize(opts.server)
//					def maxStorage = rootVolume.getMaxStorage()
//					def dataDisks = getServerDataDiskList(opts.server) //check with Dustin
//					server.osDevice = '/dev/xvda'
//					server.dataDevice = dataDisks ? dataDisks.first().deviceName : '/dev/xvda'
//					if(server.dataDevice == '/dev/xvda' || isKubernetes) {
//						server.lvmEnabled = false
//					}
//					opts.server.save(flush:true) //??
//					def createOpts = [account:opts.account, name:opts.server.name, maxMemory:maxMemory, maxStorage:maxStorage,
//									  maxCpu:maxCores, imageId:imageId, server:opts.server, zone:opts.zone, dataDisks:dataDisks, platform:'linux',
//									  externalId:opts.server.externalId, networkType:config.networkType, datastore:datastore]
//					//cloud init config
//					createOpts.hostname = server.getExternalHostname()
//					createOpts.domainName = server.getExternalDomain()
//					createOpts.fqdn = createOpts.hostname + '.' + createOpts.domainName
//					createOpts.networkConfig = networkConfigService.getNetworkConfig(opts.server, createOpts) //??
//					createOpts.isoDatastore = XenComputeUtility.findIsoDatastore(opts)
//					if(virtualImage?.isCloudInit) {
//						def cloudConfigOpts = xenProvisionService.buildCloudConfigOpts(opts.zone, opts.server, opts.installAgent, [doPing:true,
//																																   hostname:opts.server.getExternalHostname(), hosts:opts.server.getExternalHostname(), disableCloudInit:true, timezone: opts.timezone])
//						morpheusComputeService.buildCloudNetworkConfig(createOpts.platform, virtualImage, cloudConfigOpts, createOpts.networkConfig)
//						createOpts.cloudConfigUser = morpheusComputeService.buildCloudUserData(createOpts.platform, opts.userConfig, cloudConfigOpts)
//						createOpts.cloudConfigMeta = morpheusComputeService.buildCloudMetaData(createOpts.platform, "morpheus-${opts.server.id}", opts.server.getExternalHostname(), cloudConfigOpts)
//						createOpts.cloudConfigNetwork = morpheusComputeService.buildCloudNetworkData(createOpts.platform, cloudConfigOpts)
//						def cloudFileDiskName = 'morpheus_server_' + opts.server.id + '.iso'
//						createOpts.cloudConfigFile = cloudFileDiskName
//						opts.server.cloudConfigUser = createOpts.cloudConfigUser
//						opts.server.cloudConfigMeta = createOpts.cloudConfigMeta
//						opts.server.cloudConfigNetwork = createOpts.cloudConfigNetwork
//						opts.installAgent = (cloudConfigOpts.installAgent != true)
//					} else {
//						opts.createUserList = opts.userConfig.createUsers
//					}
//					//save it
//					opts.server.save(flush:true)
//					//create it
//					log.debug("create server: ${createOpts}")
//					def createResults = findOrCreateServer(createOpts)
//					log.info("create server results: ${createResults}")
//					if(createResults.success == true && createResults.vmId) {
//						opts.server.externalId = createResults.vmId
//					opts.server.save(flush:true)
//					xenProvisionService.setVolumeInfo(opts.server.volumes, createResults.volumes, opts.zone)
//					opts.server.save(flush:true)
//					def startResults = XenComputeUtility.startVm(opts, opts.server.externalId)
//					log.debug("start: ${startResults.success}")
//					if(startResults.success == true) {
//						if(startResults.error == true) {
//							opts.server.statusMessage = 'Failed to start server'
//							//ouch - delet it?
//						} else {
//							//good to go
//							def serverDetail = checkServerReady([zone:opts.zone, externalId:opts.server.externalId])
//							log.debug("serverDetail: ${serverDetail}")
//							if(serverDetail.success == true) {
//								def privateIp = serverDetail.ipAddress
//								def publicIp = serverDetail.ipAddress
//								opts.server.sshHost = privateIp
//								opts.server.internalIp = privateIp
//								opts.server.externalIp = publicIp
//								serverDetail.ipAddresses.each { interfaceName, data ->
//									ComputeServerInterface netInterface = opts.server.interfaces.find{it.name == interfaceName}
//									if(netInterface) {
//										if(data.ipAddress) {
//											def address = new NetAddress(address: data.ipAddress, type: NetAddress.AddressType.IPV4)
//											if(!address.validate()){
//												log.debug("NetAddress Errors: ${address.errors}")
//											}
//											netInterface.addToAddresses(address)
//										}
//										if(data.ipv6Address) {
//											def address = new NetAddress(address: data.ipv6Address, type: NetAddress.AddressType.IPV6)
//											if(!address.validate()){
//												log.debug("NetAddress Errors: ${address.errors}")
//											}
//											netInterface.addToAddresses(address)
//										}
//										netInterface.publicIpAddress = data.ipAddress
//										netInterface.publicIpv6Address = data.ipv6Address
//										netInterface.save(flush:true)
//									}
//								}
//								xenProvisionService.setNetworkInfo(opts.server.interfaces, serverDetail.networks)
//								opts.server.managed = true
//								opts.server.save(flush:true, failOnError:true)
//								def finalizeOpts = [server:opts.server, installAgent:opts.installAgent, createUserList:opts.createUserList,
//													processId:opts.processId, processMap:opts.processMap, processStepMap:opts.processStepMap]
//								def finalizeResults = xenProvisionService.finalizeComputeServer(opts.server, finalizeOpts)
//								def postInitResults = postInitializeServer(opts.server, opts)
//								rtn.success = finalizeResults?.success == true
//							} else {
//								opts.server.statusMessage = 'Failed to load server details'
//							}
//						}
//					} else {
//						opts.server.statusMessage = 'Failed to start server'
//					}
//				} else {
//					opts.server.statusMessage = 'Failed to create server'
//				}
//			} else {
//				opts.server.statusMessage = 'Image not found'
//			}
		} catch(e) {
//			log.error("initializeServer error: ${e}", e)
//			opts.server.statusMessage = getStatusMessage("Failed to create server: ${e.message}")
		}
//		if(rtn.success == false) {
//			try {
//				opts.server.save(flush:true)
//				ComputeServer.withNewSession {
//					ComputeServer.where { id == opts.server.id }.updateAll(status:'failed', statusMessage:opts.server.statusMessage)
//				}
//			} catch(e) {
//				log.error("initializeServer error updating error - ${e}", e)
//			}
//		}
		return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)

	}


	@Override
	ServiceResponse finalizeHost(ComputeServer computeServer) {
		return null
	}
}
