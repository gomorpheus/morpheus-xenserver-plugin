package com.morpheusdata.xen

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.BackupProvider
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.model.*
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.xen.sync.HostSync
import com.morpheusdata.xen.sync.ImagesSync
import com.morpheusdata.xen.sync.DatastoresSync
import com.morpheusdata.xen.sync.NetworkSync
import com.morpheusdata.xen.sync.PoolSync
import com.morpheusdata.xen.sync.VirtualMachineSync
import com.morpheusdata.xen.util.XenComputeUtility
import groovy.util.logging.Slf4j

@Slf4j
class XenserverCloudProvider implements CloudProvider {
	public static final String CLOUD_PROVIDER_CODE = 'xenserver'

	protected MorpheusContext context
	protected XenserverPlugin plugin

	public XenserverCloudProvider(XenserverPlugin plugin, MorpheusContext ctx) {
		super()
		this.@plugin = plugin
		this.@context = ctx
	}

	/**
	 * Grabs the description for the CloudProvider
	 * @return String
	 */
	@Override
	String getDescription() {
		return 'XenServer'
	}

	/**
	 * Returns the Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
	 * @since 0.13.0
	 * @return Icon representation of assets stored in the src/assets of the project.
	 */
	@Override
	Icon getIcon() {
		return new Icon(path:'xen-light-90x30.svg', darkPath:'xen-dark-90x30.svg')
	}

	/**
	 * Returns the circular Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
	 * @since 0.13.6
	 * @return Icon
	 */
	@Override
	Icon getCircularIcon() {
		return new Icon(path:'xenserver.svg', darkPath:'xenserver.svg')
	}

	/**
	 * Provides a Collection of OptionType inputs that define the required input fields for defining a cloud integration
	 * @return Collection of OptionType
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		def displayOrder = 0
		Collection<OptionType> options = []
		options << new OptionType(
				name: 'API URL',
				code: 'zoneType.xen.apiUrl',
				fieldName: 'apiUrl',
				displayOrder: displayOrder,
				fieldCode: 'gomorpheus.optiontype.ApiUrl',
				fieldLabel:'API URL',
				required: true,
				inputType: OptionType.InputType.TEXT,
		)
		options << new OptionType(
				name: 'Custom Port',
				code: 'zoneType.xen.customPort',
				fieldName: 'customPort',
				displayOrder: displayOrder += 10,
				fieldCode: 'gomorpheus.optiontype.CustomPort',
				fieldLabel:'Custom Port',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
		)
		options << new OptionType(
				name: 'Credentials',
				code: 'zoneType.xen.credential',
				fieldName: 'type',
				displayOrder: displayOrder += 10,
				fieldCode: 'gomorpheus.label.credentials',
				fieldLabel:'Credentials',
				required: true,
				inputType: OptionType.InputType.CREDENTIAL,
				fieldContext: 'credential',
				optionSource:'credentials',
				config: '{"credentialTypes":["username-password"]}'
		)
		options << new OptionType(
				name: 'Username',
				code: 'zoneType.xen.username',
				fieldName: 'username',
				displayOrder: displayOrder += 10,
				fieldCode: 'gomorpheus.optiontype.Username',
				fieldLabel:'Username',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				localCredential: true
		)
		options << new OptionType(
				name: 'Password',
				code: 'zoneType.xen.password',
				fieldName: 'password',
				displayOrder: displayOrder += 10,
				fieldCode: 'gomorpheus.optiontype.Password',
				fieldLabel:'Password',
				required: true,
				inputType: OptionType.InputType.PASSWORD,
				fieldContext: 'config',
				localCredential: true
		)
		options << new OptionType(
				name: 'Inventory Existing Instances',
				code: 'xenServer-import-existing',
				fieldName: 'importExisting',
				displayOrder: displayOrder += 10,
				fieldLabel: 'Inventory Existing Instances',
				required: false,
				inputType: OptionType.InputType.CHECKBOX,
				fieldContext: 'config'
		)
		options << new OptionType(
				name: 'Enable Hypervisor Console',
				code: 'xenServer-enable-hypervisor',
				fieldName: 'enableHypervisor',
				displayOrder: displayOrder += 10,
				fieldLabel: 'Enable Hypervisor Console',
				required: false,
				inputType: OptionType.InputType.CHECKBOX,
				fieldContext: 'config'
		)

		return options
	}

	/**
	 * Grabs available provisioning providers related to the target Cloud Plugin. Some clouds have multiple provisioning
	 * providers or some clouds allow for service based providers on top like (Docker or Kubernetes).
	 * @return Collection of ProvisionProvider
	 */
	@Override
	Collection<ProvisionProvider> getAvailableProvisionProviders() {
	    return this.@plugin.getProvidersByType(ProvisionProvider) as Collection<ProvisionProvider>
	}

	/**
	 * Grabs available backup providers related to the target Cloud Plugin.
	 * @return Collection of BackupProvider
	 */
	@Override
	Collection<BackupProvider> getAvailableBackupProviders() {
		return this.@plugin.getProvidersByType(BackupProvider) as Collection<BackupProvider>
	}

	/**
	 * Provides a Collection of {@link NetworkType} related to this CloudProvider
	 * @return Collection of NetworkType
	 */
	@Override
	Collection<NetworkType> getNetworkTypes() {

		Collection<NetworkType> networks = context.services.network.list(new DataQuery().withFilter(
				'code','in', ['dockerBridge', 'host', 'overlay']))

		return networks
	}

	/**
	 * Provides a Collection of {@link NetworkSubnetType} related to this CloudProvider
	 * @return Collection of NetworkSubnetType
	 */
	@Override
	Collection<NetworkSubnetType> getSubnetTypes() {
		Collection<NetworkSubnetType> subnets = []
		return subnets
	}

	/**
	 * Provides a Collection of {@link StorageVolumeType} related to this CloudProvider
	 * @return Collection of StorageVolumeType
	 */
	@Override
	Collection<StorageVolumeType> getStorageVolumeTypes() {
		Collection<StorageVolumeType> volumeTypes = []
		return volumeTypes
	}

	/**
	 * Provides a Collection of {@link StorageControllerType} related to this CloudProvider
	 * @return Collection of StorageControllerType
	 */
	@Override
	Collection<StorageControllerType> getStorageControllerTypes() {
		Collection<StorageControllerType> controllerTypes = []
		return controllerTypes
	}

	/**
	 * Grabs all {@link ComputeServerType} objects that this CloudProvider can represent during a sync or during a provision.
	 * @return collection of ComputeServerType
	 */
	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		Collection<ComputeServerType> serverTypes = []

		//xen hypervisors
		serverTypes << new ComputeServerType(code:'xenserverHypervisor', name:'Xen Hypervisor', description:'', platform:PlatformType.linux,
				nodeType:'xen-node', enabled:true, selectable:false, externalDelete:false, managed:false, controlPower:false, controlSuspend:false,
				creatable:false, computeService:'xenserverComputeService', displayOrder:0, hasAutomation:false, containerHypervisor:false,
				bareMetalHost:false, vmHypervisor:true, agentType: ComputeServerType.AgentType.none, provisionTypeCode: 'xen'
		)
		serverTypes << new ComputeServerType(code:'xenserverMetalHypervisor', name:'Xen Hypervisor - Metal', description:'',
				platform:PlatformType.linux, nodeType:'xen-node', enabled:true, selectable:false, externalDelete:false, managed:false,
				controlPower:false, controlSuspend:false, creatable:false, computeService:'xenserverComputeService', displayOrder:0,
				hasAutomation:false, containerHypervisor:false, bareMetalHost:true, vmHypervisor:true, agentType: ComputeServerType.AgentType.none,
				provisionTypeCode: 'xen'
		)

		//xen docker
		serverTypes << new ComputeServerType(code:'xenserverLinux', name:'Xen Docker Host', description:'', platform:PlatformType.linux,
				nodeType:'morpheus-node', reconfigureSupported:true, enabled:true, selectable:false, externalDelete:true, managed:true,
				controlPower:true, controlSuspend:false, creatable:false, computeService:'xenserverComputeService', displayOrder:20,
				hasAutomation:true, containerHypervisor:true, bareMetalHost:false, vmHypervisor:false, agentType: ComputeServerType.AgentType.node,
				containerEngine:'docker', computeTypeCode:'docker-host', provisionTypeCode: 'xen', optionTypes:[]
		)

		//kubernetes
		serverTypes << new ComputeServerType(code:'xenKubeMaster', name:'Xen Kubernetes Master', description:'', platform:PlatformType.linux,
				nodeType:'kube-master', reconfigureSupported: true, enabled:true, selectable:false, externalDelete:true, managed:true,
				controlPower:true, controlSuspend:true, creatable:true, supportsConsoleKeymap: true, computeService:'xenserverComputeService',
				displayOrder:10, hasAutomation:true, containerHypervisor:true, bareMetalHost:false, vmHypervisor:false,
				agentType: ComputeServerType.AgentType.guest, containerEngine:'docker', provisionTypeCode: 'xen', computeTypeCode:'kube-master',
				optionTypes:[]
		)
		serverTypes << new ComputeServerType(code:'xenKubeWorker', name:'Xen Kubernetes Worker', description:'', platform:PlatformType.linux,
				nodeType:'kube-worker', reconfigureSupported: true, enabled:true, selectable:false, externalDelete:true, managed:true, controlPower:true,
				controlSuspend:true, creatable:true, supportsConsoleKeymap: true, computeService:'xenserverComputeService', displayOrder:10,
				hasAutomation:true, containerHypervisor:true, bareMetalHost:false, vmHypervisor:false, agentType: ComputeServerType.AgentType.guest,
				containerEngine:'docker', provisionTypeCode: 'xen', computeTypeCode:'kube-worker', optionTypes:[]
		)

		serverTypes << new ComputeServerType(code:'xenserverVm', name:'Xen Instance', description:'', platform:PlatformType.linux,
				nodeType:'morpheus-vm-node', reconfigureSupported:true, enabled:true, selectable:false, externalDelete:true, managed:true,
				controlPower:true, controlSuspend:false, creatable:false, computeService:'xenserverComputeService', displayOrder:0, hasAutomation:true,
				containerHypervisor:false, bareMetalHost:false, vmHypervisor:false, agentType: ComputeServerType.AgentType.guest, guestVm:true,
				provisionTypeCode: 'xen'
		)
		serverTypes << new ComputeServerType(code:'xenserverWindowsVm', name:'Xen Instance - Windows', description:'', platform:PlatformType.windows,
				nodeType:'morpheus-windows-vm-node', reconfigureSupported:true, enabled:true, selectable:false, externalDelete:true, managed:true,
				controlPower:true, controlSuspend:false, creatable: false, computeService:'xenserverComputeService', displayOrder:0,
				hasAutomation:true, containerHypervisor:false, bareMetalHost:false, vmHypervisor:false, agentType: ComputeServerType.AgentType.guest,
				guestVm:true, provisionTypeCode: 'xen'
		)
		serverTypes << new ComputeServerType(code:'xenserverUnmanaged', name:'Xen Instance', description:'xen vm', platform:PlatformType.linux,
				nodeType:'unmanaged', reconfigureSupported:true, enabled:true, selectable:false, externalDelete:true, managed:false,
				controlPower:true, controlSuspend:false, creatable:false, computeService:'xenserverComputeService', displayOrder:99,
				hasAutomation:false, containerHypervisor:false, bareMetalHost:false, vmHypervisor:false, agentType: ComputeServerType.AgentType.none,
				managedServerType:'xenserverVm', guestVm:true, provisionTypeCode: 'xen',
				optionTypes: [
						new OptionType(
								code:'computeServerType.xen.sshHost', inputType: OptionType.InputType.TEXT, name:'sshHost', category:'computeServerType.xen', fieldName:'sshHost',
								fieldCode: 'gomorpheus.optiontype.Host', fieldLabel:'Host', fieldContext:'server', fieldSet:'sshConnection', fieldGroup:'Connection Config',
								required:false, enabled:true, editable:false, global:false, placeHolderText:null, defaultValue:null, custom:false,
								displayOrder:0, fieldClass:null, fieldSize:15
						),
						new OptionType(
								code:'computeServerType.xen.sshPort', inputType: OptionType.InputType.NUMBER, name:'sshPort', category:'computeServerType.xen', fieldName:'sshPort',
								fieldCode: 'gomorpheus.optiontype.Port', fieldLabel:'Port', fieldContext:'server', fieldSet:'sshConnection', fieldGroup:'Connection Config',
								required:false, enabled:true, editable:false, global:false, placeHolderText:'22', defaultValue:'22', custom:false,
								displayOrder:1, fieldClass:null, fieldSize:5
						),
						new OptionType(
								code:'computeServerType.xen.sshUsername', inputType: OptionType.InputType.TEXT, name:'sshUsername', category:'computeServerType.xen', fieldName:'sshUsername',
								fieldCode: 'gomorpheus.optiontype.User', fieldLabel:'User', fieldContext:'server', fieldSet:'sshConnection', fieldGroup:'Connection Config',
								required:false, enabled:true, editable:false, global:false, placeHolderText:null, defaultValue:null, custom:false, displayOrder:2,
								fieldClass:null
						),
						new OptionType(
								code:'computeServerType.xen.sshPassword', inputType: OptionType.InputType.PASSWORD, name:'sshPassword', category:'computeServerType.xen',
								fieldName:'sshPassword', fieldCode: 'gomorpheus.optiontype.Password', fieldLabel:'Password', fieldContext:'server',
								fieldSet:'sshConnection', fieldGroup:'Connection Config', required:false, enabled:true, editable:false, global:false, placeHolderText:null,
								defaultValue:null, custom:false, displayOrder:3, fieldClass:null
						)
				]
		)
		return serverTypes
	}

	/**
	 * Validates the submitted cloud information to make sure it is functioning correctly.
	 * If a {@link ServiceResponse} is not marked as successful then the validation results will be
	 * bubbled up to the user.
	 * @param cloudInfo cloud
	 * @param validateCloudRequest Additional validation information
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse validate(Cloud cloudInfo, ValidateCloudRequest validateCloudRequest) {
		return ServiceResponse.success()
	}

	/**
	 * Called when a Cloud From Morpheus is first saved. This is a hook provided to take care of initial state
	 * assignment that may need to take place.
	 * @param cloudInfo instance of the cloud object that is being initialized.
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse initializeCloud(Cloud cloudInfo) {
		ServiceResponse rtn = new ServiceResponse(success: false)
		try {
			log.debug("Refreshing Cloud: ${cloudInfo.code}")
			log.debug("config: ${cloudInfo.configMap}")

			def syncDate = new Date()
			def apiUrl = XenComputeUtility.getXenApiUrl(cloudInfo)
			def apiUrlObj = new URL(apiUrl)
			def apiHost = apiUrlObj.getHost()
			def apiPort = apiUrlObj.getPort() > 0 ? apiUrlObj.getPort() : (apiUrlObj?.getProtocol()?.toLowerCase() == 'https' ? 443 : 80)
			NetworkProxy proxySettings = cloudInfo.apiProxy
			def hostOnline = ConnectionUtils.testHostConnectivity(apiHost, apiPort, false, true, proxySettings)
			if(hostOnline) {
				refresh(cloudInfo)
				rtn.success = true
			} else {
				log.debug('offline: xen host not reachable', syncDate)
			}
		} catch(e) {
			log.error("refresh cloud error: ${e}", e)
		}
		rtn
	}

	/**
	 * Zones/Clouds are refreshed periodically by the Morpheus Environment. This includes things like caching of brownfield
	 * environments and resources such as Networks, Datastores, Resource Pools, etc.
	 * @param cloudInfo cloud
	 * @return ServiceResponse. If ServiceResponse.success == true, then Cloud status will be set to Cloud.Status.ok. If
	 * ServiceResponse.success == false, the Cloud status will be set to ServiceResponse.data['status'] or Cloud.Status.error
	 * if not specified. So, to indicate that the Cloud is offline, return `ServiceResponse.error('cloud is not reachable', null, [status: Cloud.Status.offline])`
	 */
	@Override
	ServiceResponse refresh(Cloud cloudInfo) {
		ServiceResponse rtn = new ServiceResponse(success: false)
		try {
			def testResults = XenComputeUtility.testConnection(plugin.getAuthConfig(cloudInfo))
			if(testResults.success) {

				def now = new Date().time
				new HostSync(cloudInfo, plugin).execute()
				log.debug("${cloudInfo.name}: HostSync in ${new Date().time - now}ms")

				now = new Date().time
				new ImagesSync(cloudInfo, plugin).execute()
				log.info("${cloudInfo.name}: ImagesSync in ${new Date().time - now}ms")

				now = new Date().time
				new NetworkSync(cloudInfo, plugin).execute()
				log.info("${cloudInfo.name}: NetworkSync in ${new Date().time - now}ms")

				now = new Date().time
				new DatastoresSync(cloudInfo, plugin).execute()
				log.info("${cloudInfo.name}: DatastoresSync in ${new Date().time - now}ms")

				now = new Date().time
				new PoolSync(cloudInfo, plugin).execute()
				log.info("${cloudInfo.name}: PoolSync in ${new Date().time - now}ms")

				def doInventory = cloudInfo.getConfigProperty('importExisting')
				if (doInventory == 'on' || doInventory == 'true' || doInventory == true) {
					now = new Date().time
					new VirtualMachineSync(cloudInfo, plugin, this).execute()
					log.info("${cloudInfo.name}: VirtualMachineSync in ${new Date().time - now}ms")
				}

				rtn = ServiceResponse.success()
			} else {
				rtn = ServiceResponse.error(testResults.invalidLogin == true ? 'invalid credentials' : 'error connecting')
			}
		} catch(e) {
			log.error("refresh cloud error: ${e}", e)
		}
		rtn
	}

	/**
	 * Zones/Clouds are refreshed periodically by the Morpheus Environment. This includes things like caching of brownfield
	 * environments and resources such as Networks, Datastores, Resource Pools, etc. This represents the long term sync method that happens
	 * daily instead of every 5-10 minute cycle
	 * @param cloudInfo cloud
	 */
	@Override
	void refreshDaily(Cloud cloudInfo) {
	}

	/**
	 * Called when a Cloud From Morpheus is removed. This is a hook provided to take care of cleaning up any state.
	 * @param cloudInfo instance of the cloud object that is being removed.
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteCloud(Cloud cloudInfo) {
		return ServiceResponse.success()
	}

	/**
	 * Returns whether the cloud supports {@link CloudPool}
	 * @return Boolean
	 */
	@Override
	Boolean hasComputeZonePools() {
		return false
	}

	/**
	 * Returns whether a cloud supports {@link Network}
	 * @return Boolean
	 */
	@Override
	Boolean hasNetworks() {
		return true
	}

	/**
	 * Returns whether a cloud supports {@link CloudFolder}
	 * @return Boolean
	 */
	@Override
	Boolean hasFolders() {
		return false
	}

	/**
	 * Returns whether a cloud supports {@link Datastore}
	 * @return Boolean
	 */
	@Override
	Boolean hasDatastores() {
		return false
	}

	/**
	 * Returns whether a cloud supports bare metal VMs
	 * @return Boolean
	 */
	@Override
	Boolean hasBareMetal() {
		return false
	}

	/**
	 * Indicates if the cloud supports cloud-init. Returning true will allow configuration of the Cloud
	 * to allow installing the agent remotely via SSH /WinRM or via Cloud Init
	 * @return Boolean
	 */
	@Override
	Boolean hasCloudInit() {
		return true
	}

	/**
	 * Indicates if the cloud supports the distributed worker functionality
	 * @return Boolean
	 */
	@Override
	Boolean supportsDistributedWorker() {
		return false
	}

	/**
	 * Called when a server should be started. Returning a response of success will cause corresponding updates to usage
	 * records, result in the powerState of the computeServer to be set to 'on', and related instances set to 'running'
	 * @param computeServer server to start
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	/**
	 * Called when a server should be stopped. Returning a response of success will cause corresponding updates to usage
	 * records, result in the powerState of the computeServer to be set to 'off', and related instances set to 'stopped'
	 * @param computeServer server to stop
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	/**
	 * Called when a server should be deleted from the Cloud.
	 * @param computeServer server to delete
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteServer(ComputeServer computeServer) {
		return ServiceResponse.success()
	}

	/**
	 * Grabs the singleton instance of the provisioning provider based on the code defined in its implementation.
	 * Typically Providers are singleton and instanced in the {@link Plugin} class
	 * @param providerCode String representation of the provider short code
	 * @return the ProvisionProvider requested
	 */
	@Override
	ProvisionProvider getProvisionProvider(String providerCode) {
		return getAvailableProvisionProviders().find { it.code == providerCode }
	}

	/**
	 * Returns the default provision code for fetching a {@link ProvisionProvider} for this cloud.
	 * This is only really necessary if the provision type code is the exact same as the cloud code.
	 * @return the provision provider code
	 */
	@Override
	String getDefaultProvisionTypeCode() {
		return XenserverProvisionProvider.PROVISION_PROVIDER_CODE
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
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
		return CLOUD_PROVIDER_CODE
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return 'XenServer'
	}
}
