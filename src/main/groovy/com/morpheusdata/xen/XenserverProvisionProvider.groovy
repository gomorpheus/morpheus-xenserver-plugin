package com.morpheusdata.xen


import com.morpheusdata.PrepareHostResponse
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.HostProvisionProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.NetworkUtility
import com.morpheusdata.model.*
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.SR
import com.xensource.xenapi.VM
import groovy.util.logging.Slf4j

@Slf4j
class XenserverProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider, HostProvisionProvider, ProvisionProvider.BlockDeviceNameFacet, WorkloadProvisionProvider.ResizeFacet {

	public static final String PROVIDER_NAME = 'XenServer'
	public static final String PROVIDER_CODE = 'xen'
	public static final String PROVISION_TYPE_CODE = 'xen'

	protected MorpheusContext context
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
		options << new OptionType(
				name: 'skip agent install',
				code: 'provisionType.xenserver.noAgent',
				category: 'provisionType.xenserver',
				inputType: OptionType.InputType.CHECKBOX,
				fieldName: 'noAgent',
				fieldContext: 'config',
				fieldCode: 'gomorpheus.optiontype.SkipAgentInstall',
				fieldLabel: 'Skip Agent Install',
				fieldGroup:'Advanced Options',
				displayOrder: 4,
				required: false,
				enabled: true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'Skipping Agent installation will result in a lack of logging and guest operating system statistics. Automation scripts may also be adversely affected.',
				defaultValue:null,
				custom:false,
				fieldClass:null
		)
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
		ServiceResponse rtn = new ServiceResponse(true, null, [:], null)
		def validationOpts = [:]
		try{
			if (opts.containsKey('imageId') || opts?.config?.containsKey('imageId'))
				validationOpts += [imageId: opts?.config?.imageId ?: opts?.imageId]
			if(opts.networkInterfaces) {
				validationOpts.networkInterfaces = opts.networkInterfaces
			}
			def validationResults = XenComputeUtility.validateServerConfig(validationOpts)
			if(!validationResults.success) {
				validationResults.errors?.each { it ->
					rtn.addError(it.field, it.msg)
				}
			}
		} catch(e) {
			log.error("validate container error: ${e}", e)
		}
		return rtn
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
		ProvisionResponse provisionResponse = new ProvisionResponse(success: true)

		ComputeServer server = workload.server
		Cloud cloud = server.cloud
		def imageId
		def sourceVmId
		def virtualImage
		def imageFormat = 'vhd'
		try {
			def containerConfig = workload.getConfigMap()
			def rootVolume = server.volumes?.find { it.rootVolume == true }
			def networkId = containerConfig.networkId
			def network = context.async.network.get(networkId).blockingGet()
			def sourceWorkload = context.async.workload.get(opts.cloneContainerId).blockingGet()
			def cloneContainer = opts.cloneContainerId ? sourceWorkload : null
			def morphDataStores = context.async.cloud.datastore.listById([containerConfig.datastoreId?.toLong()])
					.toMap { it.id.toLong() }.blockingGet()
			def datastore = rootVolume?.datastore ?: morphDataStores[containerConfig.datastoreId?.toLong()]
			def authConfigMap = plugin.getAuthConfig(cloud)
			if (containerConfig.imageId || containerConfig.template || server.sourceImage?.id) {
				def virtualImageId = (containerConfig.imageId?.toLong() ?: containerConfig.template?.toLong() ?: server.sourceImage.id)
				virtualImage = context.async.virtualImage.get(virtualImageId).blockingGet()
				imageId = virtualImage?.externalId
				log.info("runworkload imageId1: ${imageId}")
				if (!imageId) { //If its userUploaded and still needs uploaded
					//TODO: We need to upload ovg/vmdk stuff here
					def primaryNetwork = server.interfaces?.find { it.network }?.network
					def cloudFiles = context.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
					def imageFile = cloudFiles?.find { cloudFile -> cloudFile.name.toLowerCase().indexOf('.' + imageFormat) > -1 }
					def containerImage =
							[
									name         : virtualImage.name,
									imageSrc     : imageFile?.getURL(),
									minDisk      : virtualImage.minDisk ?: 5,
									minRam       : virtualImage.minRam ?: (512 * ComputeUtility.ONE_MEGABYTE),
									tags         : 'morpheus, ubuntu',
									imageType    : 'disk_image',
									containerType: 'vhd',
									cloudFiles   : cloudFiles,
									imageFile    : imageFile,
									imageSize    : imageFile.contentLength
							]
					def imageConfig =
							[
									zone      : cloud,
									image     : containerImage,
									name      : virtualImage.name,
									datastore : datastore,
									network   : primaryNetwork,
									osTypeCode: virtualImage?.osType?.code
							]
					imageConfig.authConfig = authConfigMap
					def imageResults = XenComputeUtility.insertTemplate(imageConfig)
					log.debug("insertTemplate: imageResults: ${imageResults}")
					if (imageResults.success == true) {
						imageId = imageResults.imageId
					}
				}
			}
			if (opts.backupSetId) { //TODO: first create backup then only we can test it...test it later...
				//if this is a clone or restore, use the snapshot id as the image
				def snapshots = context.services.backup.backupResult.list(
						new DataQuery().withFilter("backupSetId", opts.backupSetId.toLong())
								.withFilter("containerId", opts.cloneContainerId))
				def snapshot = snapshots.find { it.backupSetId == opts.backupSetId }
				def snapshotId = snapshot?.snapshotId
				sourceVmId = snapshot?.configMap?.vmId
				if (snapshotId) {
					imageId = snapshotId
				}
				if (!network && (cloneContainer || snapshot.configMap)) {
					def cloneContainerConfig = cloneContainer.configMap ?: snapshot.configMap
					networkId = cloneContainerConfig.networkId
					if (networkId) {
						containerConfig.networkId = networkId
						containerConfig.each {
							it -> workload.setConfigProperty(it.key, it.value)
						}
						workload = context.async.workload.save(workload)
						network = context.async.network.get(networkId).blockingGet()
					}
				}
			}
			log.info("runworkload imageId2: ${imageId}")
			if (imageId) {
				def userGroups = workload.instance.userGroups?.toList() ?: []
				if (workload.instance.userGroup && userGroups.contains(workload.instance.userGroup) == false) {
					userGroups << workload.instance.userGroup
				}
				server.sourceImage = virtualImage
				server.serverOs = server.serverOs ?: virtualImage.osType
				server.osType = (virtualImage.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform
				def newType = this.findVmNodeServerTypeForCloud(cloud.id, server.osType, 'xenserver-provision-provider')
				if (newType && server.computeServerType != newType) {
					server.computeServerType = newType
				}
				server = saveAndGet(server)
				def maxMemory = workload.maxMemory ?: workload.instance.plan.maxMemory
				def maxCores = workload.maxCores ?: workload.instance.plan.maxCores
				def maxStorage = this.getRootSize(workload)
				def dataDisks = server?.volumes?.findAll { it.rootVolume == false }?.sort { it.id }
				def createOpts =
						[
								account    : server.account,
								name       : server.name,
								maxMemory  : maxMemory,
								maxStorage : maxStorage,
								maxCpu     : maxCores,
								imageId    : imageId,
								server     : server,
								zone       : cloud,
								externalId : server.externalId,
								networkType: workload.getConfigProperty('networkType'),
								datastore  : datastore,
								network    : network,
								dataDisks  : dataDisks,
								platform   : server.osType,
								noAgent    : workload.getConfigProperty('noAgent')
						]
				createOpts.authConfig = authConfigMap
				createOpts.hostname = server.getExternalHostname()
				createOpts.domainName = server.getExternalDomain()
				createOpts.fqdn = createOpts.hostname
				if (createOpts.domainName) {
					createOpts.fqdn += '.' + createOpts.domainName
				}
				createOpts.networkConfig = opts.networkConfig
				//cloud init config
				createOpts.isoDatastore = findIsoDatastore(cloud.id)
				if (virtualImage?.isCloudInit) {
					createOpts.cloudConfigUser = workloadRequest?.cloudConfigUser ?: null
					createOpts.cloudConfigMeta = workloadRequest?.cloudConfigMeta ?: null
					createOpts.cloudConfigNetwork = workloadRequest?.cloudConfigNetwork ?: null
				} else if (virtualImage?.isSysprep) {
					createOpts.cloudConfigUser = workloadRequest?.cloudConfigUser ?: null
				}
				createOpts.cloudConfigFile = getCloudFileDiskName(server.id)
				createOpts.isSysprep = virtualImage?.isSysprep
				log.debug("Creating VM on Xen Server Additional Details: ${createOpts}")
				server = saveAndGet(server)
				createOpts.server = server
				def createResults
				if (sourceVmId) {
					createOpts.sourceVmId = sourceVmId
					log.debug("runworkload: calling cloneServer")
					createResults = XenComputeUtility.cloneServer(createOpts, getCloudIsoOutputStream(createOpts))
				} else {
					log.debug("runworkload: calling createProvisionServer")
					createResults = createProvisionServer(createOpts)
				}
				log.debug("Create XenServer VM Results: ${createResults}")
				if (createResults.success == true && createResults.vmId) {
					server.externalId = createResults.vmId
					provisionResponse.externalId = server.externalId
					server = saveAndGet(server)
					setVolumeInfo(server.volumes, createResults.volumes)
					server = saveAndGet(server)
					def startResults = XenComputeUtility.startVm(authConfigMap, server.externalId)
					log.debug("start: ${startResults.success}")
					if (startResults.success == true) {
						if (startResults.error == true) {
							server.statusMessage = 'Failed to start server'
							//ouch - delet it?
						} else {
							//good to go
							def serverDetail = checkServerReady([authConfig: authConfigMap, externalId: server.externalId])
							log.debug("serverDetail: ${serverDetail}")
							if (serverDetail.success == true) {
								def privateIp = serverDetail.ipAddress
								def publicIp = serverDetail.ipAddress
								serverDetail.ipAddresses.each { interfaceName, data ->
									ComputeServerInterface netInterface = server.interfaces.find { it.name == interfaceName }
									if (netInterface) {
										if (data.ipAddress) {
											def address = new NetAddress(address: data.ipAddress, type: NetAddress.AddressType.IPV4)
											if (!NetworkUtility.validateIpAddr(address.address)) {
												log.debug("NetAddress Errors: ${address}")
											}
											netInterface.addresses << address
										}
										if (data.ipv6Address) {
											def address = new NetAddress(address: data.ipv6Address, type: NetAddress.AddressType.IPV6)
											if (!NetworkUtility.validateIpAddr(address.address)) {
												log.debug("NetAddress Errors: ${address}")
											}
											netInterface.addresses << address
										}
										netInterface.publicIpAddress = data.ipAddress
										netInterface.publicIpv6Address = data.ipv6Address
										context.async.computeServer.computeServerInterface.save(netInterface).blockingGet()
									}
								}
								if (privateIp) {
									def newInterface = false
									server.internalIp = privateIp
									server.externalIp = publicIp
									server.sshHost = privateIp
								}
								//update external info
								server = saveAndGet(server)
								setNetworkInfo(server.interfaces, serverDetail.networks)
								server.osDevice = '/dev/vda'
								server.dataDevice = '/dev/vda'
								server.lvmEnabled = false
								server.sshHost = privateIp ?: publicIp
								server.managed = true
								server = saveAndGet(server)
								server.capacityInfo = new ComputeCapacityInfo(
										maxCores: 1,
										maxMemory: workload.getConfigProperty('maxMemory').toLong(),
										maxStorage: workload.getConfigProperty('maxStorage').toLong()
								)
								server.status = 'provisioned'
								saveAndGet(server)
								context.async.instance.save(workload.instance).blockingGet()
								provisionResponse.success = true
							} else {
								server.statusMessage = 'Failed to load server details'
								context.async.computeServer.save(server).blockingGet()
							}
						}
					} else {
						server.statusMessage = 'Failed to start server'
						saveAndGet(server)
					}
				} else {
					provisionResponse.setError('An unknown error occurred while making an API request to Xen.')
					provisionResponse.success = false
				}
			} else {
				server.statusMessage = 'Image not found'
			}
			provisionResponse.noAgent = opts.noAgent ?: false
			if (provisionResponse.success != true) {
				return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'vm config error', error: provisionResponse.message, data: provisionResponse)
			} else {
				return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
			}
		} catch (e) {
			log.error("initializeServer error:${e}", e)
			provisionResponse.setError(e.message)
			return new ServiceResponse(success: false, msg: e.message, error: e.message, data: provisionResponse)
		}
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
		log.debug("startWorkload: ${workload.id}")
		try {
			if(workload.server?.externalId) {
				def authConfigMap = plugin.getAuthConfig(workload.server?.cloud)
				def startResults = XenComputeUtility.startVm(authConfigMap, workload.server.externalId)
				log.debug("startWorkload: startResults: ${startResults}")
				if(startResults.success == true) {
					context.async.computeServer.updatePowerState(workload.server.id, ComputeServer.PowerState.on).blockingGet()
					return ServiceResponse.success()
				} else {
					return ServiceResponse.error("${startResults.msg}" ?: 'Failed to start vm')
				}
			} else {
				return ServiceResponse.error('vm not found')
			}
		} catch(e) {
			log.error("startContainer error: ${e}", e)
			return ServiceResponse.error(e.message)
		}
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
		log.info("Ray:: removeWorkload: opts: ${opts}")
		log.info("Ray:: removeWorkload: workload.server.id: ${workload.server.id}")
		try {
			log.info("Ray:: removeWorkload: workload.server?.externalId: ${workload.server?.externalId}")
			if(workload.server?.externalId) {
				log.info("Ray:: removeWorkload: calling stopWorkload")
				def stopResults = stopWorkload(workload)
				log.info("Ray:: removeWorkload: stopResults: ${stopResults}")
				def authConfigMap = plugin.getAuthConfig(workload.server.cloud)
				log.info("Ray:: removeWorkload: authConfigMap: ${authConfigMap}")
				log.info("Ray:: removeWorkload: opts.keepBackups: ${opts.keepBackups}")
				if(!opts.keepBackups) {
					log.info("Ray:: removeWorkload: workload.server?.snapshots?.size(): ${workload.server?.snapshots?.size()}")
					workload.server.snapshots?.each { snap ->
						log.info("Removing VM Xen Snapshot: {}", snap.externalId)
						XenComputeUtility.destroyVm(authConfigMap, snap.externalId)
					}
				}
				def removeResults = XenComputeUtility.destroyVm(authConfigMap, workload.server.externalId)
				log.info("Ray:: removeWorkload: removeResults: ${removeResults}")
				if(removeResults.success == true) {
					return ServiceResponse.success()
				} else {
					log.info("Ray:: removeWorkload: Failed to remove vm")
					return ServiceResponse.error('Failed to remove vm')
				}
			} else {
				return ServiceResponse.error('vm not found')
			}
		} catch(e) {
			log.error("Ray::removeContainer error: ${e}", e)
			return ServiceResponse.error(e.message)
		}
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
		def rtn = [success: false, msg: null]
		try {
			if (computeServer?.externalId){
				Cloud cloud = computeServer.cloud
				def stopResults = XenComputeUtility.stopVm(plugin.getAuthConfig(cloud), computeServer.externalId)
				if(stopResults.success == true){
					context.async.computeServer.updatePowerState(computeServer.id, ComputeServer.PowerState.off)
					rtn.success = true
				}
			} else {
				rtn.msg = morpheus.services.localization.get("gomorpheus.provision.xenServer.stop")
			}
		} catch(e) {
			log.error("stopServer error: ${e}", e)
			rtn.msg = e.message
		}
		return new ServiceResponse(rtn)
	}

	/**
	 * Start the server
	 * @param computeServer to start
	 * @return Response from API
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		log.info("Ray:: startServer: computeServer.id: ${computeServer?.externalId}")
		def rtn = [success: false]
		try {
			if (computeServer?.externalId) {
				def authConfigMap = plugin.getAuthConfig(computeServer.cloud)
				def startResults = XenComputeUtility.startVm(authConfigMap, computeServer.externalId)
				log.info("Ray:: startServer: startResults: ${startResults}")
				if (startResults.success == true) {
					context.async.computeServer.updatePowerState(computeServer.id, ComputeServer.PowerState.on).blockingGet()
					rtn.success = true
				}
			} else {
				rtn.msg = 'vm not found'
			}
		} catch (e) {
			log.error("Ray::startServer error: ${e}", e)
			rtn.msg = e.message
		}
		log.info("Ray:: startServer: rtn: ${rtn}")
		return new ServiceResponse(rtn)
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

	protected ComputeServer saveAndGet(ComputeServer server) {
		def saveSuccessful = context.async.computeServer.bulkSave([server]).blockingGet()
		if(!saveSuccessful) {
			log.warn("Error saving server: ${server?.id}" )
		}
		return context.async.computeServer.get(server.id).blockingGet()
	}

	@Override
	ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "prepareHost: ${server} ${hostRequest} ${opts}"

		def prepareResponse = new PrepareHostResponse(computeServer: server, disableCloudInit: false, options: [sendIp: true])
		ServiceResponse<PrepareHostResponse> rtn = ServiceResponse.prepare(prepareResponse)

		try {
			VirtualImage virtualImage
			Long computeTypeSetId = server.typeSet?.id
			if(computeTypeSetId) {
				ComputeTypeSet computeTypeSet = morpheus.async.computeTypeSet.get(computeTypeSetId).blockingGet()
				if(computeTypeSet.workloadType) {
					WorkloadType workloadType = morpheus.async.workloadType.get(computeTypeSet.workloadType.id).blockingGet()
					virtualImage = workloadType.virtualImage
				}
			}
			if(!virtualImage) {
				rtn.msg = "No virtual image selected"
			} else {
				server.sourceImage = virtualImage
				saveAndGet(server)
				rtn.success = true
			}
		} catch(e) {
			rtn.msg = "Error in prepareHost: ${e}"
			log.error("${rtn.msg}, ${e}", e)

		}
		return rtn
	}

	@Override
	ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug("runHost: ${server} ${hostRequest} ${opts}")

		ProvisionResponse provisionResponse = new ProvisionResponse()
		try {
			def layout = server?.layout
			def typeSet = server.typeSet
			def serverGroupType = layout?.groupType
			Boolean isKubernetes = serverGroupType?.providerType == 'kubernetes'
			def config = server.getConfigMap()
			Cloud cloud = server.cloud
			Account account = server.account
			def imageFormat = 'vhd'
			def imageType = config.templateTypeSelect ?: 'default'
			def imageId
			def virtualImage
			Map authConfig = plugin.getAuthConfig(cloud)
			def rootVolume = server.volumes?.find{it.rootVolume == true}
			def datastoreId = rootVolume.datastore?.id
			def datastore = context.async.cloud.datastore.listById([datastoreId?.toLong()]).firstOrError().blockingGet()
			log.debug("runHost datastore: ${datastore}")

			if(layout && typeSet) {
				Long computeTypeSetId = server.typeSet?.id
				if(computeTypeSetId) {
					ComputeTypeSet computeTypeSet = morpheus.services.computeTypeSet.get(computeTypeSetId)
					WorkloadType workloadType = computeTypeSet.getWorkloadType()
					if(workloadType) {
						Long workloadTypeId = workloadType.id
						WorkloadType containerType = morpheus.services.containerType.get(workloadTypeId)
						Long virtualImageId = containerType.virtualImage.id
						virtualImage = morpheus.services.virtualImage.get(virtualImageId)
						def imageLocation = virtualImage?.imageLocations.find{it.refId == cloud.id && it.refType == "ComputeZone"}
						imageId = imageLocation?.externalId
					}
				}
			} else if(imageType == 'custom' && config.imageId) {
				virtualImage = server.sourceImage
				imageId = virtualImage.externalId
			} else {
				virtualImage  = new VirtualImage(code: 'xen.image.morpheus.ubuntu.20.04-v1.amd64')
			}
			if(!imageId) {
				def cloudFiles = context.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
				def imageFile = cloudFiles?.find{cloudFile -> cloudFile.name.toLowerCase().indexOf('.' + imageFormat) > -1}
				def primaryNetwork = server.interfaces?.find{it.network}?.network
				def containerImage = [
						name			: virtualImage.name,
						imageSrc		: imageFile?.getURL(),
						minDisk			: virtualImage.minDisk ?: 5,
						minRam			: virtualImage.minRam,
						tags			: 'morpheus, ubuntu',
						imageType		: 'disk_image',
						containerType	: 'vhd',
						cloudFiles		: cloudFiles,
						imageFile		: imageFile
				]
				def imageConfig = [
						zone		: cloud,
						image		: containerImage,
						name		: virtualImage.name,
						datastore	: datastore,
						network		: primaryNetwork,
						osTypeCode	: virtualImage?.osType?.code
				]
				imageConfig.authConfig = authConfig
				def imageResults = XenComputeUtility.insertTemplate(imageConfig)
				if(imageResults.success == true) {
					imageId = imageResults.imageId
				}
			}
			if(imageId) {
				server.sourceImage = virtualImage
				def maxMemory = server.maxMemory ?: server.plan.maxMemory
				def maxCores = server.maxCores ?: server.plan.maxCores
				def maxStorage = rootVolume.maxStorage
				def dataDisks = server?.volumes?.findAll{it.rootVolume == false}?.sort{it.id}
				server.osDevice = '/dev/xvda'
				server.dataDevice = dataDisks ? dataDisks.first().deviceName : '/dev/xvda'
				if(server.dataDevice == '/dev/xvda' || isKubernetes) {
					server.lvmEnabled = false
				}
				def createOpts = [
						account		: account,
						name		: server.name,
						maxMemory	: maxMemory,
						maxStorage	: maxStorage,
						maxCpu		: maxCores,
						imageId		: imageId,
						server		: server,
						zone		: cloud,
						dataDisks	: dataDisks,
						platform	: 'linux',
						externalId	: server.externalId,
						networkType	: config.networkType,
						datastore	: datastore
				]
				//cloud init config
				createOpts.hostname = server.getExternalHostname()
				createOpts.domainName = server.getExternalDomain()
				createOpts.fqdn = createOpts.hostname + '.' + createOpts.domainName
				createOpts.cloudConfigUser = hostRequest.cloudConfigUser
				createOpts.cloudConfigMeta = hostRequest.cloudConfigMeta
				createOpts.cloudConfigNetwork = hostRequest.cloudConfigNetwork
				createOpts.networkConfig = hostRequest.networkConfiguration
				createOpts.isSysprep = virtualImage?.isSysprep
				createOpts.isoDatastore = findIsoDatastore(cloud.id)
				createOpts.cloudConfigFile = getCloudFileDiskName(server.id)

				context.async.computeServer.save(server).blockingGet()
				//create it
				log.debug("create server: ${createOpts}")
				createOpts.authConfig = authConfig
				def createResults = findOrCreateServer(createOpts)
				if(createResults.success == true && createResults.vmId) {
					def startResults = XenComputeUtility.startVm(authConfig, createResults.vmId)
					provisionResponse.externalId = createResults.vmId
					log.debug("start: ${startResults.success}")
					if(startResults.success == true) {
						if(startResults.error == true) {
							server.statusMessage = 'Failed to start server'
						} else {
							provisionResponse.success = true
						}
					} else {
						server.statusMessage = 'Failed to start server'
					}
				} else {
					server.statusMessage = 'Failed to create server'
				}
			} else {
				server.statusMessage = 'Image not found'
			}
			if (provisionResponse.success != true) {
				return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'vm config error', error: provisionResponse.message, data: provisionResponse)
			} else {
				return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
			}
		} catch(e) {
			log.error("Error in runHost method: ${e}", e)
			provisionResponse.setError(e.message)
			return new ServiceResponse(success: false, msg: e.message, error: e.message, data: provisionResponse)
		}
	}

	def findOrCreateServer(opts) {
		def rtn = [success:false]
		def found = false
		if(opts.server.externalId) {
			def serverDetail = getServerDetail(opts)
			if(serverDetail.success == true) {
				found = true
				rtn.success = true
				rtn.vm = serverDetail.vm
				rtn.vmId = serverDetail.vmId
				rtn.vmRecord = serverDetail.vmRecord
				rtn.volumes = serverDetail.volumes
				rtn.networks = serverDetail.networks
				if(serverDetail.ipAddress) {
					rtn.ipAddress = serverDetail.ipAddress
				} else {
					//try to start it?
				}
			}
		}
		if(found == true) {
			return rtn
		} else {
			return createServer(opts)
		}
	}

	def createServer(opts) {
		def rtn = [success:false]
		if(!opts.imageId) {
			rtn.error = 'Please specify a template'
		} else if(!opts.name) {
			rtn.error = 'Please specify a name'
		} else {
			rtn = createProvisionServer(opts)
		}
		return rtn
	}

	def createProvisionServer(opts) {
		def rtn = [success: false]
		log.debug "createServer: ${opts}"
		try {
			def config = XenComputeUtility.getXenConnectionSession(opts.authConfig)
			opts.connection = config.connection
			def srRecord = SR.getByUuid(config.connection, opts.datastore.externalId)
			def template = VM.getByUuid(config.connection, opts.imageId)
			def newVm = template.createClone(config.connection, opts.name)
			newVm.setIsATemplate(config.connection, false)
			//set ram
			def newMemory = (opts.maxMemory).toLong()
			def newStorage = (opts.maxStorage).toLong()
			newVm.setMemoryLimits(config.connection, newMemory, newMemory, newMemory, newMemory)
			//set cpu
			if (opts.maxCpu) {
				newVm.setVCPUsMax(config.connection, opts.maxCpu)
				newVm.setVCPUsAtStartup(config.connection, opts.maxCpu)
			}
			//disk
			def newConfig = newVm.getOtherConfig(config.connection)
			def newDisks = newConfig.get('disks')
			if (newDisks) {
				newDisks = newDisks.replaceAll('sr=\"\"', 'sr=\"' + srRecord.getUuid(config.connection) + '\"')
				newConfig.put('disks', newDisks)
				newVm.setOtherConfig(config.connection, newConfig)
			}
			//add cloud init iso
			def cdResults = opts.cloudConfigFile ? XenComputeUtility.insertCloudInitDisk(opts, getCloudIsoOutputStream(opts)) : [success: false]
			log.debug("runworkload: createProvisionServer: cdResults: ${cdResults}")
			def rootVolume = opts.server.volumes?.find{it.rootVolume == true}
			if (rootVolume) {
				rootVolume.unitNumber = "0"
				context.async.storageVolume.save(rootVolume).blockingGet()
			}
			def lastDiskIndex = 0
			if (cdResults.success == true) {
				lastDiskIndex = XenComputeUtility.createCdromVbd(opts, newVm, cdResults.vdi, (lastDiskIndex + 1).toString()).deviceId.toInteger()
			}

			//add optional data disk
			if (opts.dataDisks?.size() > 0) {
				opts.dataDisks?.eachWithIndex { disk, diskIndex ->
					def dataSrRecord = SR.getByUuid(config.connection, disk.datastore.externalId)
					def dataVdi = XenComputeUtility.createVdi(opts, dataSrRecord, disk.maxStorage)
					def dataVbd = XenComputeUtility.createVbd(opts, newVm, dataVdi, (lastDiskIndex + 1).toString())
					lastDiskIndex = dataVbd.deviceId?.toInteger() ?: lastDiskIndex + 1
					if (dataVbd.success == true) {
						dataVbd.vbd.setUnpluggable(opts.connection, true)
						def deviceId = dataVbd.vbd.getUserdevice(opts.connection)
						if (deviceId) {
							disk.unitNumber = "${deviceId}"
						} else {
							disk.unitNumber = lastDiskIndex
						}
						context.async.storageVolume.save(disk).blockingGet()
					}
				}
			}
			//No longer required, need to check later
//			else if (opts.diskSize) {
//
//				def dataVdi = XenComputeUtility.createVdi(opts, srRecord, opts.diskSize)
//				def dataVbd = XenComputeUtility.createVbd(opts, newVm, dataVdi, (lastDiskIndex + 1).toString())
//				lastDiskIndex = dataVbd.deviceId.toInteger()
//			}
			//set network
			XenComputeUtility.setVmNetwork(opts, newVm, opts.networkConfig)
			def rootVbd = XenComputeUtility.findRootDrive(opts, newVm)
			def rootVbdSize = rootVbd.getVirtualSize(config.connection)
			log.info("resizing root drive: ${rootVbd} with size: ${rootVbdSize} to: ${newStorage}")
			if (rootVbd && newStorage > rootVbdSize)
				rootVbd.resize(config.connection, newStorage)
			rtn.success = true
			rtn.vm = newVm
			rtn.vmRecord = rtn.vm.getRecord(config.connection)
			rtn.vmId = rtn.vmRecord.uuid
			rtn.volumes = XenComputeUtility.getVmVolumes(config, newVm)
			rtn.networks = XenComputeUtility.getVmNetworks(config, newVm)
		} catch (e) {
			log.error("createServer error: ${e}", e)
		}
		return rtn
	}

	def getServerDetail(opts) {
		def getServerDetail = XenComputeUtility.getVirtualMachine(opts.authConfig, opts.externalId)
		return getServerDetail
	}

	def setNetworkInfo(serverInterfaces, externalNetworks, newInterface = null) {
		log.info("serverInterfaces: ${serverInterfaces}, externalNetworks: ${externalNetworks}")
		try {
			if(externalNetworks?.size() > 0) {
				serverInterfaces?.eachWithIndex { networkInterface, index ->
					if(networkInterface.externalId) {
						//check for changes?
					} else {
						def matchNetwork = externalNetworks.find{networkInterface.internalId == it.uuid}
						if(!matchNetwork) {
							def displayOrder = "${networkInterface.displayOrder}"
							matchNetwork = externalNetworks.find{displayOrder == it.deviceIndex}
						}
						if(matchNetwork) {
							networkInterface.externalId = "${matchNetwork.deviceIndex}"
							networkInterface.internalId = "${matchNetwork.uuid}"
							if(networkInterface.type == null) {
								networkInterface.type = new ComputeServerInterfaceType(code: 'xenNetwork')
							}
							context.async.computeServer.computeServerInterface.save(networkInterface).blockingGet()
						}
					}
				}
			}
		} catch(e) {
			log.error("setNetworkInfo error: ${e}", e)
		}
	}

	@Override
	ServiceResponse<ProvisionResponse> waitForHost(ComputeServer server){
		log.debug("waitForHost: ${server}")
		def provisionResponse = new ProvisionResponse()
		ServiceResponse<ProvisionResponse> rtn = ServiceResponse.prepare(provisionResponse)
		try {
			Map authConfig = plugin.getAuthConfig(server.cloud)
			def serverDetail = checkServerReady([authConfig: authConfig, externalId: server.externalId])
			if (serverDetail.success == true) {
				provisionResponse.privateIp = serverDetail.ipAddress
				provisionResponse.publicIp = serverDetail.ipAddress
				provisionResponse.externalId = server.externalId
				def finalizeResults = finalizeHost(server)
				if(finalizeResults.success == true) {
					provisionResponse.success = true
					rtn.success = true
				}
			}
		} catch (e){
			log.error("Error waitForHost: ${e}", e)
			rtn.success = false
			rtn.msg = "Error in waiting for Host: ${e}"
		}

		return rtn
	}

	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		ServiceResponse rtn = ServiceResponse.prepare()
		log.debug("finalizeHost: ${server?.id}")
		try {
			Map authConfig = plugin.getAuthConfig(server.cloud)
			def serverDetail = checkServerReady([authConfig: authConfig, externalId: server.externalId])

			if (serverDetail.success == true){
				serverDetail.ipAddresses.each { interfaceName, data ->
					ComputeServerInterface netInterface = server.interfaces?.find{it.name == interfaceName}
					if(netInterface) {
						if(data.ipAddress) {
							def address = new NetAddress(address: data.ipAddress, type: NetAddress.AddressType.IPV4)
							if(!NetworkUtility.validateIpAddr(address.address)){
								log.debug("NetAddress Errors: ${address}")
							}
							netInterface.addresses << address
						}
						if(data.ipv6Address) {
							def address = new NetAddress(address: data.ipv6Address, type: NetAddress.AddressType.IPV6)
							if(!NetworkUtility.validateIpAddr(address.address)){
								log.debug("NetAddress Errors: ${address}")
							}
							netInterface.addresses << address
						}
						netInterface.publicIpAddress = data.ipAddress
						netInterface.publicIpv6Address = data.ipv6Address
						context.async.computeServer.computeServerInterface.save(netInterface).blockingGet()
					}
				}
				setNetworkInfo(server.interfaces, serverDetail.networks)
				context.async.computeServer.save(server).blockingGet()
				rtn.success = true
			}

		} catch (e){
			rtn.success = false
			rtn.msg = "Error in finalizing server: ${e.message}"
			log.error("Error in finalizeWorkload: {}", e, e)
		}
		return rtn
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Boolean canAddVolumes() {
		return true
	}

	@Override
	Boolean canCustomizeRootVolume() {
		return true
	}

	@Override
	HostType getHostType() {
		return HostType.vm
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
	Boolean canCustomizeDataVolumes() {
		return true
	}

	@Override
	Boolean canResizeRootVolume() {
		return true
	}

	@Override
	Boolean canReconfigureNetwork() {
		return true
	}

	@Override
	Boolean hasNodeTypes() {
		return true;
	}

	@Override
	Boolean createDefaultInstanceType() {
		return false
	}

	def setVolumeInfo(serverVolumes, externalVolumes) {
		log.debug("volumes: ${externalVolumes}")
		try {
			def maxCount = externalVolumes?.size()
			serverVolumes.sort{it.displayOrder}.eachWithIndex { volume, index ->
				if(index < maxCount) {
					if(volume.externalId) {
						//check for changes?
						log.debug("volume already assigned: ${volume.externalId}")
					} else if(volume.internalId) {
						externalVolumes.each { externalVolume ->
							if(externalVolume.uuid == volume.internalId) {
								volume.externalId = "${externalVolume.deviceIndex}"
								volume.unitNumber = "${externalVolume.deviceIndex}"
								volume.displayOrder = externalVolume.deviceIndex?.toLong()
								/*volume.save(flush: true)*/ // Ask:
								context.async.storageVolume.save(volume).blockingGet()
							}
						}
					} else {
						def unitFound = false
						def volumeOrder = volume.displayOrder ? "${volume.displayOrder}" : null
						def volumeUnit = volume.unitNumber ? "${volume.unitNumber}" : null
						log.debug("finding volume: ${volume.id} - ${volumeOrder} - ${volumeUnit}")
						externalVolumes.each { externalVolume ->
							if(unitFound == false && volumeUnit == externalVolume.deviceIndex.toString()) {
								log.debug("found matching unit disk: ${volume.displayOrder}")
								unitFound = true
								volume.externalId = "${externalVolume.deviceIndex}"
								volume.internalId = externalVolume.uuid
								//volume.save() Ask:
								context.async.storageVolume.save(volume).blockingGet()
							}
						}
						if(unitFound != true) {
							externalVolumes.each { externalVolume ->
								if(unitFound == false && volumeOrder == externalVolume.deviceIndex) {
									log.debug("found matching order disk: ${volume.displayOrder}")
									unitFound = true
									volume.externalId = "${externalVolume.deviceIndex}"
									volume.internalId = externalVolume.uuid
									volume.unitNumber = "${externalVolume.deviceIndex}"
									//volume.save()
									context.async.storageVolume.save(volume).blockingGet()
								}
							}
						}
						if(unitFound != true) {
							def sizeRange = [min:(volume.maxStorage - ComputeUtility.ONE_GIGABYTE), max:(volume.maxStorage + ComputeUtility.ONE_GIGABYTE)]
							externalVolumes.each { externalVolume ->
								def sizeCheck = externalVolume.size
								def externalKey = externalVolume.deviceIndex
								log.debug("volume size check - ${externalKey}: ${sizeCheck} between ${sizeRange.min} and ${sizeRange.max}")
								if(unitFound != true && sizeCheck > sizeRange.min && sizeCheck < sizeRange.max) {
									def dupeCheck = serverVolumes.find{it.externalId == externalKey}
									if(!dupeCheck) {
										//assign a match to the volume
										unitFound = true
										volume.externalId = "${externalVolume.deviceIndex}"
										volume.internalId = externalVolume.uuid
										volume.unitNumber = "${externalVolume.deviceIndex}"
										//volume.save()
										context.async.storageVolume.save(volume).blockingGet()
									} else {
										log.debug("found dupe volume")
									}
								}
							}
						}
					}
				}
			}
		} catch(e) {
			log.error("setVolumeInfo error: ${e}", e)
		}
	}

	def checkServerReady(opts) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 5l)
				def serverDetail
				try {
					serverDetail = getServerDetail(opts)
				} catch(ex) {
					log.warn('An error occurred trying to get VM Details while waiting for server to be ready. This could be because the vm is not yet ready and can safely be ignored. ' +
							'We will automatically retry. Any detailed exceptions will be logged at debug level.')
					log.debug("Errors from get server detail: ${ex.message}", ex)
				}
				log.debug("serverDetail: ${serverDetail}")
				if(serverDetail?.success == true && serverDetail?.vmRecord && serverDetail?.ipAddress) {
					if(serverDetail.ipAddress) {
						rtn.success = true
						rtn.ipAddress = serverDetail.ipAddress
						if(serverDetail.vmNetworks) {
							rtn.ipAddresses = [:]
							serverDetail.vmNetworks.each {key, value ->
								def keyInfo = key.tokenize('/')
								def interfaceName = "eth${keyInfo[0]}"
								rtn.ipAddresses[interfaceName] = rtn.ipAddresses[interfaceName] ?: [:]
								if(keyInfo[1] == 'ip') {
									rtn.ipAddresses[interfaceName].ipAddress = value
									if(interfaceName == 'eth0') {
										rtn.ipAddress = value
									}
								} else { //ipv6
									rtn.ipAddresses[interfaceName].ipv6Address = value
								}
							}
						}
						rtn.vmRecord = serverDetail.vmRecord
						rtn.vm = serverDetail.vm
						rtn.vmId = serverDetail.vmId
						rtn.vmDetails = serverDetail.vmDetails
						rtn.volumes = serverDetail.volumes
						rtn.networks = serverDetail.networks
						pending = false
					}
				}
				attempts ++
				if(attempts > 300)
					pending = false

			}
		} catch(e) {
			log.error("An Exception in checkServerReady: ${e.message}",e)
		}
		return rtn
	}

	def findIsoDatastore(Long cloudId) {
		def rtn
		try {
			def dsList = context.services.cloud.datastore.list(
					new DataQuery().withFilter("category", "xenserver.sr.${cloudId}")
							.withFilter("type", "iso")
							.withFilter("storageSize", ">", 1024l * 100l)
							.withFilter("active", true))
			if (dsList?.size() > 0) {
				rtn = dsList?.size() > 0 ? dsList.first() : null
			}
		} catch (e) {
			log.error("findIsoDatastore error: ${e}", e)
		}
		return rtn
	}

	def getCloudIsoOutputStream(Map opts = [:]) {
		def isoOutput = context.services.provision.buildIsoOutputStream(
				opts.isSysprep, PlatformType.valueOf(opts.platform), opts.cloudConfigMeta, opts.cloudConfigUser, opts.cloudConfigNetwork)
		return isoOutput
	}

	def getCloudFileDiskName (Long serverId) {
		return 'morpheus_server_' + serverId + '.iso'
	}

	@Override
	String[] getDiskNameList() {
		//xvdb is skipped to make way for the cdrom
		return ['xvda', 'xvdc', 'xvdd', 'xvde', 'xvdf', 'xvdg', 'xvdh', 'xvdi', 'xvdj', 'xvdk', 'xvdl'] as String[]
	}

	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		log.info("Ray:: resizeWorkload: workload?.id: ${workload?.id}")
		log.info("Ray:: resizeWorkload: resizeRequest: ${resizeRequest}")
		log.info("Ray:: resizeWorkload: opts: ${opts}")
		def rtn = [success: false, supported: true]
		ComputeServer computeServer = workload.server
		log.info("Ray:: resizeWorkload: computeServer?.externalId: ${computeServer?.externalId}")
		Cloud cloud = computeServer.cloud
		log.info("Ray:: resizeWorkload: cloud?.id: ${cloud?.id}")
		def authConfigMap = plugin.getAuthConfig(cloud)
		log.info("Ray:: resizeWorkload: authConfigMap: ${authConfigMap}")
		try {
			//log.info("resizing vm: ${opts}")
			log.info("Ray:: resizeWorkload: computeServer?.status: ${computeServer?.status}")
			computeServer.status = 'resizing'
			computeServer = saveAndGet(computeServer)
			log.info("Ray:: resizeWorkload: computeServer?.status1: ${computeServer?.status}")
			//def servicePlanOptions = opts.servicePlanOptions ?: [:]
			log.info("Ray:: resizeWorkload: cresizeRequest.plan: ${resizeRequest.plan}")
			ServicePlan plan = resizeRequest.plan
			log.info("Ray:: resizeWorkload: resizeRequest.maxMemory: ${resizeRequest.maxMemory}")
			log.info("Ray:: resizeWorkload: plan.customMaxMemory: ${plan.customMaxMemory}")
			log.info("Ray:: resizeWorkload: plan.maxMemory: ${plan.maxMemory}")
			def requestedMemory = plan.customMaxMemory ? (resizeRequest.maxMemory ?: plan.maxMemory) : plan.maxMemory
			log.info("Ray:: resizeWorkload: requestedMemory: ${requestedMemory}")
			log.info("Ray:: resizeWorkload:  plan.customCores: ${ plan.customCores}")
			log.info("Ray:: resizeWorkload: resizeRequest?.maxCores: ${resizeRequest?.maxCores}")
			log.info("Ray:: resizeWorkload: plan.maxCores: ${plan.maxCores}")
			def requestedCores = plan.customCores ? (resizeRequest?.maxCores ?: plan.maxCores) : plan.maxCores
			log.info("Ray:: resizeWorkload: requestedCores: ${requestedCores}")
			log.info("Ray:: resizeWorkload: workload.maxMemory: ${workload.maxMemory}")
			log.info("Ray:: resizeWorkload: workload.getConfigProperty: ${workload.getConfigProperty('maxMemory')}")
			def currentMemory = workload.maxMemory ?: workload.getConfigProperty('maxMemory')?.toLong()
			log.info("Ray:: resizeWorkload: currentMemory: ${currentMemory}")
			log.info("Ray:: resizeWorkload: workload.maxCores: ${workload.maxCores}")
			def currentCores = workload.maxCores ?: 1
			log.info("Ray:: resizeWorkload: currentCores: ${currentCores}")
			def neededMemory = requestedMemory - currentMemory
			log.info("Ray:: resizeWorkload: neededMemory: ${neededMemory}")
			def neededCores = (requestedCores ?: 1) - (currentCores ?: 1)
			log.info("Ray:: resizeWorkload: neededCores: ${neededCores}")
			def allocationSpecs = [externalId: computeServer.externalId, maxMemory: requestedMemory, maxCpu: requestedCores]
			log.info("Ray:: resizeWorkload: allocationSpecs: ${allocationSpecs}")
			log.info("Ray:: resizeWorkload: instance.containers?.size(): ${instance.containers?.size()}")
			def multipleContainers = instance.containers?.size() > 1
			def modified = false
			def stopped = false
			def stopResults
			//check for stop - we have weird math going on
			log.info("Ray:: resizeWorkload: computeServer.hotResize: ${computeServer.hotResize}")
			log.info("Ray:: resizeWorkload: resizeRequest.volumesUpdate?.size(): ${resizeRequest.volumesUpdate?.size()}")
			def doStop = computeServer.hotResize != true && (neededMemory > 100000000l || neededMemory < -100000000l || neededCores != 0 || resizeRequest.volumesUpdate?.size() > 0)
			// || volumeSyncLists?.volumeDeletes?.size() > 0)
			log.info("Ray:: resizeWorkload: doStop: ${doStop}")
			if (doStop) {
				log.info("Ray:: stopping vm for resize: memory ${neededMemory} cores ${neededCores}")
				stopped = true
				opts.stopped = stopped
				//instanceTaskService.runShutdownTasks(instance, opts.userId)
				//workload.server = computeServer// check: do we need this line, do we need to set server back to workload as updated earlier
				stopResults = stopWorkload(workload)
				log.info("Ray:: resizeWorkload: stopResults: ${stopResults}")
			}
			//adjust plan size
			def checkVal = (neededMemory > 100000000l || neededMemory < -100000000l || neededCores != 0)
			log.info("Ray:: resizeWorkload: checkVal: ${checkVal}")
			if (neededMemory > 100000000l || neededMemory < -100000000l || neededCores != 0) {
				log.info("resizing vm: ${allocationSpecs}")
				log.info("Ray:: resizeWorkload: resizing vm: ${allocationSpecs}")
				def allocationResults = XenComputeUtility.adjustVmResources(authConfigMap, computeServer.externalId, allocationSpecs)
				log.info("Ray:: resizeWorkload: allocationResults: ${allocationResults}")
				if (allocationResults.success) {
					modified = true
					workload.setConfigProperty('maxMemory', requestedMemory)
					workload.maxMemory = requestedMemory.toLong()
					workload.setConfigProperty('maxCores', (requestedCores ?: 1))
					workload.maxCores = (requestedCores ?: 1).toLong()
					workload.plan = plan
					workload.server.plan = plan//.addVolumes // check........
					workload.server.maxCores = (requestedCores ?: 1).toLong()
					workload.server.maxMemory = requestedMemory.toLong()
					log.info("Ray:: resizeWorkload: before saving workload")
					context.async.workload.save(workload).blockingGet()
					log.info("Ray:: resizeWorkload: After saving workload")
					computeServer = workload.server
					log.info("Ray:: resizeWorkload: before saving server")
					computeServer = saveAndGet(computeServer)
					log.info("Ray:: resizeWorkload: after saving server")
				}
			}
			def maxStorage = 0
			log.info("Ray:: resizeWorkload: opts.volumes: ${opts.volumes}")
			if (opts.volumes) {
				//def rootDisk = getContainerRootDisk(container)
				def newCounter = computeServer.volumes?.size()
				log.info("Ray:: resizeWorkload: newCounter: ${newCounter}")
				//maxStorage = volumeSyncLists.totalStorage
				//volumes
				//log.info("volumeSyncLists?.volumeUpdates? ${volumeSyncLists?.volumeUpdates}")
				log.info("Ray:: resizeWorkload: resizeRequest.volumesUpdate.size(): ${resizeRequest.volumesUpdate.size()}")
				resizeRequest.volumesUpdate?.each { volumeUpdate ->
					log.info("Ray:: resizeWorkload: volumeUpdate: ${volumeUpdate}")
					log.info("Ray:: resizeWorkload: volumeUpdate.existingModel: ${volumeUpdate?.existingModel}")
					log.info("Ray:: resizeWorkload: volumeUpdate.existingModel?.name: ${volumeUpdate?.existingModel?.name}")
					log.info("Ray:: resizeWorkload: volumeUpdate.updateProps: ${volumeUpdate.updateProps}")
					StorageVolume existing = volumeUpdate.existingModel
					Map updateProps = volumeUpdate.updateProps
					log.info("resizing vm storage: ${volumeUpdate}")
					//existing disk - resize it
					log.info("Ray:: resizeWorkload: updateProps.maxStorage: ${updateProps.maxStorage}")
					log.info("Ray:: resizeWorkload: existing.maxStorage: ${existing.maxStorage}")
					if (updateProps.maxStorage > existing.maxStorage) {
						def resizeDiskConfig = [diskSize: updateProps.maxStorage, diskIndex: existing.externalId, uuid: existing.internalId]
						log.info("Ray:: resizeWorkload: resizeDiskConfig: ${resizeDiskConfig}")
						def resizeResults = XenComputeUtility.resizeVmDisk(authConfigMap, computeServer.externalId, resizeDiskConfig)
						log.info("Ray:: resizeWorkload: resizeResults: ${resizeResults}")
						def storageVolumeId = existing.id
						log.info("Ray:: resizeWorkload: storageVolumeId: ${storageVolumeId}")
						def existingVolume = context.async.storageVolume.get(existing.id).blockingGet()
						log.info("Ray:: resizeWorkload: existingVolume?.maxStorage: ${existingVolume?.maxStorage}")
						existingVolume.maxStorage = updateProps?.maxStorage
						log.info("Ray:: resizeWorkload: updateProps?.maxStorage: ${updateProps?.maxStorage}")
						context.async.storageVolume.save(existingVolume).blockingGet()
						modified = true
					}
				}
				log.info("Ray:: resizeWorkload: resizeRequest.volumesAdd?.size(): ${resizeRequest.volumesAdd?.size()}")
				resizeRequest.volumesAdd.each { volumeAdd ->
					//new disk add it
					log.info("Ray:: resizeWorkload: volumeAdd: ${volumeAdd}")
					//log.info("resizing vm adding storage: ${volumeAdd}")
					def addDiskConfig = [diskSize: volumeAdd.maxStorage, diskName: "morpheus_data_${newCounter}", diskIndex: newCounter]
					log.info("Ray:: resizeWorkload: addDiskConfig: ${addDiskConfig}")
					log.info("Ray:: resizeWorkload: volumeAdd.datastoreId: ${volumeAdd.datastoreId}")
					addDiskConfig.datastoreId = getDatastoreExternalId(volumeAdd.datastoreId)
					log.info("Ray:: resizeWorkload: addDiskConfig.datastoreId: ${addDiskConfig.datastoreId}")
					def addDiskResults = XenComputeUtility.addVmDisk(authConfigMap, computeServer.externalId, addDiskConfig)
					log.info("Ray:: resizeWorkload: addDiskResults: ${addDiskResults}")
					log.info("Ray:: resizeWorkload: addDiskResults.datastore: ${addDiskResults.datastore}")
					//log.info("addDiskResults: ${addDiskResults} - ${addDiskResults.datastore}")
					if (addDiskResults.success == true) {
						volumeAdd.volume.internalId = addDiskResults.volume?.uuid
						volumeAdd.volume.unitNumber = addDiskResults.volume?.deviceIndex
						volumeAdd.volume.displayOrder = addDiskResults.volume?.deviceIndex?.toLong()
						def allStorageVolumeTypes = context.async.storageVolume.storageVolumeType.listAll().toMap { it.id }.blockingGet()
						log.info("Ray:: resizeWorkload: allStorageVolumeTypes: ${allStorageVolumeTypes}")
						log.info("Ray:: resizeWorkload: volumeAdd.storageType: ${volumeAdd.storageType}")
						// check: new code
						def volumeType = allStorageVolumeTypes[volumeAdd.storageType?.toLong()] // check: new code
						log.info("Ray:: resizeWorkload: volumeType: ${volumeType}")
						//def newVolume = buildStorageVolume(container.server.account, container.server, volumeUpdate.volume, newCounter)
						def newVolume = new StorageVolume(
								refType: 'ComputeZone',
								refId: cloud.id,
								regionCode: computeServer.region?.regionCode,
								account: computeServer.account,
								maxStorage: volumeAdd.maxStorage,
								maxIOPS: volumeAdd.maxIops,
								type: volumeType, // check
								externalId: volumeAdd.externalId, //check
								//deviceName: deviceName,
								//deviceDisplayName: extractDiskDisplayName(deviceName),
								name: volumeAdd.name,
								displayOrder: newCounter,
								status: 'provisioned',
								unitNumber: addDiskResults.volume?.deviceIndex
								//rootVolume: ['/dev/sda1','/dev/xvda','xvda','sda1','sda'].contains(deviceName)
						)
						log.info("Ray:: resizeWorkload: newVolume: ${newVolume}")
						newVolume.uniqueId = "morpheus-vol-${instance.id}-${workload.id}-${newCounter}"
						log.info("Ray:: resizeWorkload: newVolume.uniqueId: ${newVolume.uniqueId}")
						//newVolume.save(flush:true)
						//newVolume.maxStorage = volumeAdd.volume.size.toInteger() * ComputeUtility.ONE_GIGABYTE // check: working of this line
						computeServer.volumes << newVolume
						setVolumeInfo(computeServer.volumes, addDiskResults.volumes)

						context.async.storageVolume.create([newVolume], computeServer).blockingGet()
						computeServer = saveAndGet(computeServer)
						workload.server = computeServer
						context.async.workload.save(workload).blockingGet()
						newCounter++
						modified = true
					} else {
						log.warn("Ray:: error adding disk: ${addDiskResults}")
					}
				}
				// Delete any removed volumes
				log.info("Ray:: resizeWorkload: resizeRequest.volumesDelete?.size(): ${resizeRequest.volumesDelete?.size()}")
				resizeRequest.volumesDelete.each { volume ->
					log.info("Ray:: resizeWorkload: volume.internalId: ${volume.internalId}")
					log.info("resize deleting volume : ${volume.internalId}")
					def deleteResults = XenComputeUtility.deleteVmDisk(authConfigMap, computeServer.externalId, volume.internalId)
					log.info("Ray:: resizeWorkload: deleteResults: ${deleteResults}")
					if (deleteResults.success == true) {
						log.info("resize delete complete: ${deleteResults.success}")
						workload = context.async.workload.get(workload.id).blockingGet()
						computeServer = workload.server
						computeServer.volumes.remove(volume)
						context.async.storageVolume.remove([volume], computeServer, true).blockingGet()
						computeServer = saveAndGet(computeServer)
						workload.server = computeServer
						context.async.workload.save(workload).blockingGet()
					}
				}
			}

			//networks
			log.info("Ray:: resizeWorkload: opts.networkInterfaces: ${opts.networkInterfaces}")
			if (opts.networkInterfaces) {
				log.debug("build network networkSyncLists: ${opts.networkInterfaces}")
				log.debug("container ${workload.id} network networkSyncLists:")
				//controllers
				log.info("Ray:: resizeWorkload: resizeRequest?.interfacesUpdate?.size(): ${resizeRequest?.interfacesUpdate?.size()}")
				resizeRequest?.interfacesUpdate?.eachWithIndex { networkUpdate, index ->
					log.info("Ray:: resizeWorkload: networkUpdate.existingModel: ${networkUpdate.existingModel}")
					if (networkUpdate.existingModel) {
						log.info("modifying network: ${networkUpdate}")

					} else {

					}
				}

				log.info("Ray:: resizeWorkload: resizeRequest.interfacesAdd?.size(): ${resizeRequest.interfacesAdd?.size()}")
				resizeRequest.interfacesAdd.eachWithIndex { networkAdd, index ->
					//log.info("adding network: ${networkAdd}")
					log.info("Ray:: resizeWorkload: networkAdd: ${networkAdd}")
					log.info("Ray:: resizeWorkload: index: ${index}")
					def newIndex = workload.server.interfaces?.size()
					log.info("Ray:: resizeWorkload: newIndex: ${newIndex}")
					def newType = new ComputeServerInterfaceType(code: 'xenNetwork')
					log.info("Ray:: resizeWorkload: networkAdd.network: ${networkAdd.network}")
					log.info("Ray:: resizeWorkload: networkAdd.network.id: ${networkAdd.network.id}")
					def newNetwork = context.async.network.listById([networkAdd.network.id.toLong()]).firstOrError().blockingGet()
					log.info("Ray:: resizeWorkload: newNetwork?.externalId: ${newNetwork?.externalId}")
					def networkConfig = [networkIndex: newIndex, networkUuid: newNetwork.externalId]
					log.info("Ray:: resizeWorkload: networkConfig: ${networkConfig}")
					def networkResults = XenComputeUtility.addVmNetwork(authConfigMap, computeServer.externalId, networkConfig)
					log.info("Ray:: resizeWorkload: networkResults: ${networkResults}")
					//log.info("network results: ${networkResults}")
					if (networkResults.success == true) {
						//def newInterface = buildComputeServerInterface(instance.account, instance, container.server, networkUpdate.network, newIndex, [:])
						log.info("Ray:: resizeWorkload: computeServer.platform: ${computeServer.platform}")
						def platform = computeServer.platform
						def nicName
						if (platform == 'windows') {
							nicName = (index == 0) ? 'Ethernet' : 'Ethernet ' + (index + 1)
						} else if (platform == 'linux') {
							nicName = "eth${index}"
						} else {
							nicName = "eth${index}"
						}
						log.info("Ray:: resizeWorkload: nicName: ${nicName}")
						def newInterface = new ComputeServerInterface([
								name            : nicName,
								//ipAddress       : nic?.getPrivateIpAddress(),
								network         : newNetwork,
								displayOrder    : newIndex,
								primaryInterface: networkAdd?.network?.isPrimary ? true : false
						])
						log.info("Ray:: resizeWorkload: newInterface: ${newInterface}")
						if (networkResults.networkIndex)
							newInterface.externalId = "${networkResults.networkIndex}"
						if (networkResults.uuid)
							newInterface.internalId = networkResults.uuid
						newInterface.uniqueId = "morpheus-nic-${instance.id}-${workload.id}-${newIndex}"
						//newInterface.addresses += new NetAddress(type: NetAddress.AddressType.IPV4, address: nic?.getPrivateIpAddress()) //check: aws added code
						log.info("Ray:: resizeWorkload: newInterface1: ${newInterface}")
						newInterface = context.async.computeServer.computeServerInterface.create(newInterface).blockingGet()
						computeServer.interfaces << newInterface
						computeServer = saveAndGet(computeServer)
						workload.server = computeServer
					}
				}

				log.info("Ray:: resizeWorkload: resizeRequest?.interfacesDelete?.size(): ${resizeRequest?.interfacesDelete?.size()}")
				resizeRequest?.interfacesDelete?.eachWithIndex { networkDelete, index ->
					authConfigMap.stopped = opts.stopped
					def deleteResults = XenComputeUtility.deleteVmNetwork(authConfigMap, computeServer.externalId, networkDelete.internalId)
					log.info("Ray:: resizeWorkload: deleteResults: ${deleteResults}")
					log.debug("deleteResults: ${deleteResults}")
					if (deleteResults.success == true) {
						context.async.computeServer.computeServerInterface.remove([networkDelete], computeServer).blockingGet()
						computeServer.interfaces.remove(networkDelete)
						computeServer = saveAndGet(computeServer)
						workload.server = computeServer
					}
				}
			}
			workload = context.async.workload.save(workload).blockingGet()
			if (!rtn.error && maxStorage)
				workload.maxStorage = maxStorage
			computeServer.status = 'provisioned'
			computeServer = saveAndGet(computeServer)
			workload.server = computeServer
			if (stopped == true) {
				startWorkload(workload)
				if (workload.status == Workload.Status.running) {
					//def tmpInstance = Instance.get(instance.id)
					//instanceTaskService.runStartupTasks(tmpInstance, opts.userId)
				}
			}
			rtn.success = true
		} catch (e) {
			log.error("Ray:: Unable to resize container: ${e.message}", e)
			workload.server.status = 'provisioned'
			workload.server = saveAndGet(workload.server)
			rtn.errors << "${e}"
			rtn.msg = 'Error resizing container'
		}
		log.info("Ray:: resizeWorkload: rtn: ${rtn}")
		return new ServiceResponse(success: rtn.success, data: [supported: rtn.supported])
	}

	def getDatastoreExternalId(datastoreId) {
		if (datastoreId == 'auto' || datastoreId == 'autoCluster') {
			return null
		}
		def datastore = context.async.cloud.datastore.get(datastoreId?.toLong()).blockingGet()
		return datastore?.externalId
	}
}
