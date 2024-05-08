package com.morpheusdata.xen

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.BackupExecutionProvider
import com.morpheusdata.core.backup.response.BackupExecutionResponse
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Snapshot
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.xen.util.XenComputeUtility
import groovy.util.logging.Slf4j

import java.util.zip.ZipOutputStream

@Slf4j
class XenserverBackupExecutionProvider implements BackupExecutionProvider {

//	Plugin plugin
	XenserverPlugin plugin
	MorpheusContext morpheusContext

	XenserverBackupExecutionProvider(Plugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}
	
	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	MorpheusContext getMorpheus() {
		return morpheusContext
	}

	/**
	 * Add additional configurations to a backup. Morpheus will handle all basic configuration details, this is a
	 * convenient way to add additional configuration details specific to this backup provider.
	 * @param backupModel the current backup the configurations are applied to.
	 * @param config the configuration supplied by external inputs.
	 * @param opts optional parameters used for configuration.
	 * @return a {@link ServiceResponse} object. A ServiceResponse with a false success will indicate a failed
	 * configuration and will halt the backup creation process.
	 */
	@Override
	ServiceResponse configureBackup(Backup backup, Map config, Map opts) {
		return ServiceResponse.success(backup)
	}

	/**
	 * Validate the configuration of the backup. Morpheus will validate the backup based on the supplied option type
	 * configurations such as required fields. Use this to either override the validation results supplied by the
	 * default validation or to create additional validations beyond the capabilities of option type validation.
	 * @param backupModel the backup to validate
	 * @param config the original configuration supplied by external inputs.
	 * @param opts optional parameters used for
	 * @return a {@link ServiceResponse} object. The errors field of the ServiceResponse is used to send validation
	 * results back to the interface in the format of {@code errors['fieldName'] = 'validation message' }. The msg
	 * property can be used to send generic validation text that is not related to a specific field on the model.
	 * A ServiceResponse with any items in the errors list or a success value of 'false' will halt the backup creation
	 * process.
	 */
	@Override
	ServiceResponse validateBackup(Backup backup, Map config, Map opts) {
		return ServiceResponse.success(backup)
	}

	/**
	 * Create the backup resources on the external provider system.
	 * @param backupModel the fully configured and validated backup
	 * @param opts additional options used during backup creation
	 * @return a {@link ServiceResponse} object. A ServiceResponse with a success value of 'false' will indicate the
	 * creation on the external system failed and will halt any further backup creation processes in morpheus.
	 */
	@Override
	ServiceResponse createBackup(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Delete the backup resources on the external provider system.
	 * @param backupModel the backup details
	 * @param opts additional options used during the backup deletion process
	 * @return a {@link ServiceResponse} indicating the results of the deletion on the external provider system.
	 * A ServiceResponse object with a success value of 'false' will halt the deletion process and the local refernce
	 * will be retained.
	 */
	@Override
	ServiceResponse deleteBackup(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Delete the results of a backup execution on the external provider system.
	 * @param backupResultModel the backup results details
	 * @param opts additional options used during the backup result deletion process
	 * @return a {@link ServiceResponse} indicating the results of the deletion on the external provider system.
	 * A ServiceResponse object with a success value of 'false' will halt the deletion process and the local refernce
	 * will be retained.
	 */
	@Override
	ServiceResponse deleteBackupResult(BackupResult backupResult, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * A hook into the execution process. This method is called before the backup execution occurs.
	 * @param backupModel the backup details associated with the backup execution
	 * @param opts additional options used during the backup execution process
	 * @return a {@link ServiceResponse} indicating the success or failure of the execution preperation. A success value
	 * of 'false' will halt the execution process.
	 */
	@Override
	ServiceResponse prepareExecuteBackup(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Provide additional configuration on the backup result. The backup result is a representation of the output of
	 * the backup execution including the status and a reference to the output that can be used in any future operations.
	 * @param backupResultModel
	 * @param opts
	 * @return a {@link ServiceResponse} indicating the success or failure of the method. A success value
	 * of 'false' will halt the further execution process.
	 */
	@Override
	ServiceResponse prepareBackupResult(BackupResult backupResultModel, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Initiate the backup process on the external provider system.
	 * @param backup the backup details associated with the backup execution.
	 * @param backupResult the details associated with the results of the backup execution.
	 * @param executionConfig original configuration supplied for the backup execution.
	 * @param cloud cloud context of the target of the backup execution
	 * @param computeServer the target of the backup execution
	 * @param opts additional options used during the backup execution process
	 * @return a {@link ServiceResponse} indicating the success or failure of the backup execution. A success value
	 * of 'false' will halt the execution process.
	 */
	ServiceResponse<BackupExecutionResponse> updateBackupStatus(BackupExecutionResponse backupExecutionResponse){
		ServiceResponse<BackupExecutionResponse> rtn = ServiceResponse.prepare(backupExecutionResponse)
		rtn.data.backupResult.status = BackupResult.Status.IN_PROGRESS
		rtn.data.updates = true
		log.info("RAZI :: updateBackupStatus : RTN: ${rtn}")
		return rtn
	}

	@Override
	ServiceResponse<BackupExecutionResponse> executeBackup(Backup backup, BackupResult backupResult, Map executionConfig, Cloud cloud, ComputeServer server, Map opts) {
		log.debug("Executing backup {} with result {}", backup.id, backupResult.id)
		BackupExecutionResponse backupExecutionResponse = new BackupExecutionResponse(backupResult)
		ServiceResponse<BackupExecutionResponse> rtn = ServiceResponse.prepare(backupExecutionResponse)

		Map authConfig = plugin.getAuthConfig(cloud)
//		String vmUuid = server.externalId

		try {
			def snapshotName = "${server.name}.${server.id}.${System.currentTimeMillis()}".toString()
			def snapshotOpts = [zone:cloud, server:server, externalId:server.externalId, snapshotName:snapshotName, snapshotDescription:'']
			snapshotOpts.authConfig = authConfig
			log.info("RAZI :: snapshotOpts: ${snapshotOpts}")
			def outputPath = executionConfig.backupConfig.workingPath //executionConfig
			log.info("RAZI :: outputPath: ${outputPath}")
			def outputFile = new File(outputPath)
			log.info("RAZI :: outputFile: ${outputFile}")
			outputFile.mkdirs()
			log.info("RAZI :: mkdirs : SUCCESS")
			//update status
//			updateBackupStatus(backupResult.id, 'IN_PROGRESS', [containerConfig:container?.configMap]) // skip
			updateBackupStatus(backupExecutionResponse)
			log.info("RAZI :: updateBackupStatus : SUCCESS1")
			//remove cloud init
			if(server.sourceImage && server.sourceImage.isCloudInit && server.serverOs?.platform != 'windows') {
				def taskResult1 = getPlugin().morpheus.executeCommandOnServer(server, 'sudo rm -f /etc/cloud/cloud.cfg.d/99-manual-cache.cfg; sudo cp /etc/machine-id /tmp/machine-id-old ; sync', false, server.sshUsername, server.sshPassword, null, null, null, null, true, true).blockingGet()
				log.info("RAZI :: taskResult1: ${taskResult1}")
			}
			//take snapshot
			def snapshotResults
			try {
				snapshotResults = XenComputeUtility.snapshotVm(snapshotOpts, server.externalId)
				log.info("RAZI :: snapshotResults: ${snapshotResults}")
			} finally {
				//restore cloud init
				if(server.sourceImage && server.sourceImage.isCloudInit && server.serverOs?.platform != 'windows') {
					def taskResult2 = getPlugin().morpheus.executeCommandOnServer(server, "sudo bash -c \"echo 'manual_cache_clean: True' >> /etc/cloud/cloud.cfg.d/99-manual-cache.cfg\"; sudo cat /tmp/machine-id-old > /etc/machine-id ; sudo rm /tmp/machine-id-old ; sync", false, server.sshUsername, server.sshPassword, null, null, null, null, true, true).blockingGet()
					log.info("RAZI :: taskResult2: ${taskResult2}")
				}
			}
//			log.info("backup complete: {}", snapshotResults)
			log.info("RAZI :: backup complete: {}", snapshotResults)
			if(snapshotResults.success) {
				//save the snapshot
				Snapshot snapshotRecord = new Snapshot(account:server.account, externalId:snapshotResults.snapshotId, name:"snapshot-${new Date().time}")
				log.info("RAZI :: snapshotRecord: ${snapshotRecord}")
				morpheusContext.async.snapshot.save(snapshotRecord).blockingGet()

				server.snapshots << snapshotRecord
				morpheusContext.async.computeServer.save(server).blockingGet()
				log.info("RAZI :: backup.copyToStore: ${backup.copyToStore}")
				if(backup.copyToStore == true) {
					log.info("snapshot complete - saving to target storage: {}", snapshotResults)

//					def providerInfo = backupStorageService.getBackupStorageProviderConfig(backup.account, backup.id)// Dustin will check
//					def archiveName = "backup.${backupResult.id}.zip"
//					def zipFile = providerInfo.provider[providerInfo.bucketName]["backup.${backup.id}/${archiveName}"]
					def bucket = morpheusContext.async.backup.getBackupStorageBucket(backup.account, backup.id)
					def provider = morpheusContext.services.backup.getBackupStorageProvider(bucket.id)
					def archiveName = "backup.${backupResult.id}.zip"
					def zipFile = provider[providerInfo.bucketName]["backup.${backup.id}/${archiveName}"]
					log.info("RAZI :: zipFile: ${zipFile}")
					//we have to do some piping magic for this one
					PipedOutputStream outStream = new PipedOutputStream()
					InputStream istream  = new PipedInputStream(outStream)
					BufferedOutputStream buffOut = new BufferedOutputStream(outStream,1048576)
					ZipOutputStream zipStream = new ZipOutputStream(buffOut)
					def saveResults = [success:false]
					def saveThread = Thread.start {
						try {
							log.info("RAZI :: inside Thread.start")
							zipFile.setInputStream(istream)
							zipFile.save()
							saveResults.archiveSize = zipFile.getContentLength()
							saveResults.success = true
							log.info("RAZI :: saveResults: ${saveResults}")
						} catch(ex) {
							log.error("Error Saving Backup File! ${ex.message}",ex)
							try {
								zipStream.close()
								log.info("RAZI :: zipStream : CLOSED")
							} catch(ex2) {
								//dont care about exception on this but we need to close it on save failure thread in the event we need to cutoff the stream
							}
						}
					}
					//download it to output path
					def instance = morpheusContext.async.instance.get(backup.instanceId).blockingGet()
					def exportOpts = [zone:cloud, targetDir:outputPath, targetZipStream:zipStream, snapshotId:snapshotResults.snapshotId,
									  vmName:"${instance.name}.${backup.containerId}"]
					exportOpts.authConfig = authConfig
					log.info("RAZI :: exportOpts: ${exportOpts}")
					log.debug("exportOpts: {}", exportOpts)
					def exportResults = XenComputeUtility.exportVm(exportOpts, snapshotResults.snapshotId)
					log.debug("exportResults: {}", exportResults)
					saveThread.join()
					log.info("RAZI :: exportResults: ${exportResults}")
					if(saveResults.success == true) {
//						def statusMap = [backupResultId:rtn.backupResultId, executorIP:rtn.ipAddress, destinationPath:outputPath, snapshotId: snapshotResults.snapshotId,
//										 providerType:providerInfo.providerType, targetBucket:providerInfo.bucketName, imageType:'xva',
//										 targetDirectory:"backup.${backup.id}", backupSizeInMb:(saveResults.archiveSize ?: 1).div(ComputeUtility.ONE_MEGABYTE),
//										 targetArchive:archiveName, success:true]
//						statusMap.config = [snapshotId:snapshotResults.snapshotId, snapshotName:snapshotName, vmId:snapshotResults.externalId]
//						updateBackupStatus(backupResult.id, null, statusMap)
						rtn.success = true
						updateBackupStatus(backupExecutionResponse)
						log.info("RAZI :: updateBackupStatus : if(saveResults.success == true) : SUCCESS")
					} else {
//						def error = saveResults.error ?: "Failed to save backup result"
//						def statusMap = [backupResultId:rtn.backupResultId, executorIP:rtn.ipAddress, destinationPath:outputPath,
//										 snapshotExtracted:false, backupSizeInMb:0, success:false, errorOutput:error.encodeAsBase64()]
//						updateBackupStatus(backupResult.id, null, statusMap)
						updateBackupStatus(backupExecutionResponse)
						log.info("RAZI :: updateBackupStatus : if(saveResults.success == true) - else : SUCCESS")
					}
				} else {
//					def statusMap = [backupResultId:rtn.backupResultId, executorIP:rtn.ipAddress, destinationPath:outputPath,
//									 providerType:'xen', providerBasePath:'xen', targetBucket:snapshotResults.externalId, targetDirectory:snapshotName,
//									 targetArchive:snapshotResults.snapshotId, backupSizeInMb:0, snapshotExtracted:false, success:true]
//					statusMap.config = [snapshotId:snapshotResults.snapshotId, snapshotName:snapshotName, vmId:snapshotResults.externalId]
//					updateBackupStatus(backupResult.id, null, statusMap)
					updateBackupStatus(backupExecutionResponse)
					log.info("RAZI :: updateBackupStatus : if(backup.copyToStore == true) - else : SUCCESS")
				}
			} else {
//				//error
//				def statusMap = [backupResultId:rtn.backupResultId, executorIP:rtn.ipAddress, destinationPath:outputPath,
//								 snapshotExtracted:false, backupSizeInMb:0, success:false, errorOutput:snapshotResults.error?.toString()?.encodeAsBase64()]
//				updateBackupStatus(backupResult.id, null, statusMap)
				updateBackupStatus(backupExecutionResponse)
				log.info("RAZI :: updateBackupStatus : if(snapshotResults.success) - else : SUCCESS")
			}
//			clearBackupProcessId(backupResult)
			rtn.success = true
			log.info("RAZI :: RTN : last: ${rtn}")
		} catch(e) {
			log.error("error in executeBackup: ${e}", e)
//			rtn.message = e.getMessage()
//			def error = "Failed to execute backup"
//			def statusMap = [backupResultId:rtn.backupResultId, executorIP:rtn.ipAddress,
//							 snapshotExtracted:false, backupSizeInMb:0, success:false, errorOutput:error.encodeAsBase64()]
//			updateBackupStatus(backupResult.id, null, statusMap)
			updateBackupStatus(backupResult)
		}

		return rtn
	}

	/**
	 * Periodically call until the backup execution has successfully completed. The default refresh interval is 60 seconds.
	 * @param backupResult the reference to the results of the backup execution including the last known status. Set the
	 *                     status to a canceled/succeeded/failed value from one of the BackupStatusUtility values
	 *                     to end the execution process.
	 * @return a {@link ServiceResponse} indicating the success or failure of the method. A success value
	 * of 'false' will halt the further execution process.n
	 */
	@Override
	ServiceResponse<BackupExecutionResponse> refreshBackupResult(BackupResult backupResult) {
		return ServiceResponse.success(new BackupExecutionResponse(backupResult))
	}
	
	/**
	 * Cancel the backup execution process without waiting for a result.
	 * @param backupResultModel the details associated with the results of the backup execution.
	 * @param opts additional options.
	 * @return a {@link ServiceResponse} indicating the success or failure of the backup execution cancellation.
	 */
	@Override
	ServiceResponse cancelBackup(BackupResult backupResultModel, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Extract the results of a backup. This is generally used for packaging up a full backup for the purposes of
	 * a download or full archive of the backup.
	 * @param backupResultModel the details associated with the results of the backup execution.
	 * @param opts additional options.
	 * @return a ServiceResponse indicating the success or failure of the backup extraction.
	 */
	@Override
	ServiceResponse extractBackup(BackupResult backupResultModel, Map opts) {
		//TODO: This method doesn't work in the embedded integration, so its likely to implement using
		// legacy code.
		/*def rtn = [success:false]
		try {
			rtn.backupResultId = backupResult.id
			def backup = Backup.get(backupResult.backup.id)
			def container = Container.read(backup.containerId)
			def instance = Instance.read(container.instanceId)
			def server = getBackupComputeServer(backup)
			def zone = zoneService.loadFullZone(server.zoneId)
			def zoneType = ComputeZoneType.read(zone.zoneTypeId)
			String outputPath = backupStorageService.getWorkingBackupPath(backup.id, backupResult.id)
			def outputFile = new File(outputPath)
			outputFile.mkdirs()
			//prep export
			def exportOpts = [zone:zone, targetDir:outputPath, snapshotId:backupResult.snapshotId, vmName:"${instance.name}.${container.id}"]
			log.debug("exportOpts: {}", exportOpts)
			def exportResults = XenComputeUtility.exportVm(exportOpts, backupResult.snapshotId)
			log.debug("exportResults: {}", exportResults)
			def saveResults = backupStorageService.saveBackupResults(backup.account, outputFile.getPath(), backup.id)
			if(saveResults.success == true) {
				def statusMap = [backupResultId:rtn.backupResultId, destinationPath:outputPath, providerType:saveResults.providerType,
								 providerBasePath:saveResults.basePath, targetBucket:saveResults.targetBucket,
								 targetDirectory:saveResults.targetDirectory, snapshotExtracted:true, targetArchive:saveResults.targetArchive,
								 backupSizeInMb:(saveResults.archiveSize ?: 1).div(ComputeUtility.ONE_MEGABYTE), success:true]
				statusMap.config = [snapshotId:snapshotResults.snapshotId, snapshotName:snapshotName, vmId:snapshotResults.externalId]
				updateBackupStatus(backupResult.id, null, statusMap)
				rtn.success = true
				rtn.destinationPath = outputPath
				rtn.snapshotId = statusMap.snapshotId
				rtn.targetBucket = statusMap.targetBucket
				rtn.targetProvider = statusMap.providerType
				rtn.targetBase = statusMap.providerBasePath
				rtn.targetDirectory = statusMap.targetDirectory
				rtn.targetArchive = statusMap.targetArchive
			} else {
				def error = saveResults.error ?: "Failed to save backup result"
				rtn.message = error
			}
		} catch(e) {
			log.error("extractBackup: ${e}", e)
			def error = "Failed to extract backup"
			rtn.message = "Failed to extract backup: ${e.getMessage()}"
		}
		return rtn*/
		return ServiceResponse.error()
	}

}		
