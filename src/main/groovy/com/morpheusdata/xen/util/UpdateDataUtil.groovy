package com.morpheusdata.xen.util

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.WorkloadType
import com.morpheusdata.xen.XenserverProvisionProvider
import groovy.util.logging.Slf4j

@Slf4j
class UpdateDataUtil {

	/**
	 * Finds the custom xen container types and updates the stat type code for proper stat collection.
	 */
	static updateContainerTypeStatTypeCode(MorpheusContext morpheusContext) {
		try {
			// get container type from morpheus context where provision type is the same as provision provider code
			// and the statType code is "xen"
			morpheusContext.async.workloadType.list(
				new DataQuery().withFilter('provisionType', XenserverProvisionProvider.PROVISION_TYPE_CODE).withFilter('statTypeCode', 'xen')
			).doOnNext { WorkloadType workloadType ->
				// fix the stat type code
				workloadType.statTypeCode = 'vm'
			}.buffer(50)
			.subscribe() { List<WorkloadType> workloadTypes ->
				// save the changes
				morpheusContext.async.workloadType.bulkSave(workloadTypes).subscribe()
			}
		} catch (Exception e) {
			log.error("Error in UpdateDataUtil: ${e.message}", e)
		}
	}
}
