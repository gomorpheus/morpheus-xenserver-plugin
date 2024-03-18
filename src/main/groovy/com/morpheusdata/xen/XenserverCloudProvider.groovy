package com.morpheusdata.xen

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.BackupProvider
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.model.*
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse

class XenserverCloudProvider implements CloudProvider {
	public static final String CLOUD_PROVIDER_CODE = 'xenserver.cloud'

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
		return 'Morpheus XenServer Plugin'
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
		// TODO: Need to change this, as of now no circular icon available
		return new Icon(path:'xen-light-140x40.svg', darkPath:'xen-dark-140x40.svg')
	}

	/**
	 * Provides a Collection of OptionType inputs that define the required input fields for defining a cloud integration
	 * @return Collection of OptionType
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		Collection<OptionType> options = []
		options << new OptionType(
				name: 'API URL',
				code: 'zoneType.xen.apiUrl',
				fieldName: 'apiUrl',
				displayOrder: 0,
				fieldCode: 'gomorpheus.optiontype.ApiUrl',
				category: 'zoneType.xen',
				fieldLabel:'API URL',
				required: true,
				enabled: true,
				editable: false,
				global: false,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				fieldGroup: 'Connection Config',
				custom: false,
				fieldSize:15
		)
		options << new OptionType(
				name: 'Credentials',
				code: 'zoneType.xen.credential',
				fieldName: 'type',
				displayOrder: 1,
				fieldCode: 'gomorpheus.label.credentials',
				category: 'zoneType.xen',
				fieldLabel:'Credentials',
				required: true,
				enabled: true,
				editable: true,
				global: false,
				inputType: OptionType.InputType.CREDENTIAL,
				fieldContext: 'credential',
				fieldGroup: 'Connection Config',
				defaultValue: 'local',
				custom: false,
				optionSource:'credentials',
				config: '{"credentialTypes":["username-password"]}'
		)
		options << new OptionType(
				name: 'Username',
				code: 'zoneType.xen.username',
				fieldName: 'username',
				displayOrder: 2,
				fieldCode: 'gomorpheus.optiontype.Username',
				category: 'zoneType.xen',
				fieldLabel:'Username',
				required: true,
				enabled: true,
				editable: false,
				global: false,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'config',
				fieldGroup: 'Connection Config',
				custom: false,
				fieldSize: 15,
				localCredential: true
		)
		options << new OptionType(
				name: 'Password',
				code: 'zoneType.xen.password',
				fieldName: 'password',
				displayOrder: 3,
				fieldCode: 'gomorpheus.optiontype.Password',
				category: 'zoneType.xen',
				fieldLabel:'Password',
				required: true,
				enabled: true,
				editable: false,
				global: false,
				inputType: OptionType.InputType.PASSWORD,
				fieldContext: 'config',
				fieldGroup: 'Connection Config',
				custom: false,
				fieldSize: 15,
				localCredential: true
		)
		options << new OptionType(
				name: 'Import Existing',
				code: 'zoneType.xen.importExisting',
				fieldName: 'importExisting',
				displayOrder: 4,
				fieldCode: 'gomorpheus.optiontype.ImportExistingInstances',
				category: 'zoneType.xen',
				fieldLabel: 'Import Existing Instances',
				required: true,
				enabled: true,
				editable: false,
				global: false,
				inputType: OptionType.InputType.CHECKBOX,
				helpBlock:'Turn this feature on to import existing virtual machines from Xen.',
				fieldContext: 'config',
				fieldGroup: 'Options',
				defaultValue: 'off',
				custom: false,
				fieldSize: 15
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
		Collection<NetworkType> networks = []

		networks << new NetworkType(
				code              : 'dockerBridge',
				name              : 'Docker Bridge',
				overlay           : false,
				creatable         : true,
				nameEditable      : false,
				cidrEditable      : false,
				dhcpServerEditable: false,
				dnsEditable       : false,
				gatewayEditable   : false,
				ipv6Editable      : false,
				vlanIdEditable    : false,
				cidrRequired      : true,
				canAssignPool     : false,
				deletable         : true,
				hasNetworkServer  : false,
				hasCidr           : true,
				optionTypes       : []
		)
		networks << new NetworkType(
				code              : 'host',
				name              : 'Host',
				overlay           : false,
				creatable         : true,
				nameEditable      : false,
				cidrEditable      : false,
				dhcpServerEditable: false,
				dnsEditable       : false,
				gatewayEditable   : false,
				ipv6Editable      : false,
				vlanIdEditable    : false,
				cidrRequired      : true,
				canAssignPool     : false,
				deletable         : true,
				hasNetworkServer  : false,
				hasCidr           : true,
				optionTypes       : []
		)
		networks << new NetworkType(
				code              : 'overlay',
				name              : 'Overlay',
				overlay           : false,
				creatable         : true,
				nameEditable      : false,
				cidrEditable      : false,
				dhcpServerEditable: false,
				dnsEditable       : false,
				gatewayEditable   : false,
				ipv6Editable      : false,
				vlanIdEditable    : false,
				cidrRequired      : true,
				canAssignPool     : false,
				deletable         : true,
				hasNetworkServer  : false,
				hasCidr           : true,
				optionTypes       : []
		)

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
		return ServiceResponse.success()
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
		return ServiceResponse.success()
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
		return true
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
		return true
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
		return 'Morpheus XenServer Plugin'
	}
}
