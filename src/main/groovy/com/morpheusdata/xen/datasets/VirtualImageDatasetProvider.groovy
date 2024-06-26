package com.morpheusdata.xen.datasets

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.data.DatasetInfo
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.providers.AbstractDatasetProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.VirtualImage
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class VirtualImageDatasetProvider extends AbstractDatasetProvider<VirtualImage, Long> {

	public static final providerName = 'XCP-ng Virtual Images'
	public static final providerNamespace = 'xcpng'
	public static final providerKey = 'xcpVirtualImages'
	public static final providerDescription = 'Get virtual images for XCP-ng provisioning.'

	VirtualImageDatasetProvider(Plugin plugin, MorpheusContext morpheus) {
		this.plugin = plugin
		this.morpheusContext = morpheus
	}

	@Override
	DatasetInfo getInfo() {
		return new DatasetInfo(
			name: providerName,
			namespace: providerNamespace,
			key: providerKey,
			description: providerDescription
		)
	}

	@Override
	Class<VirtualImage> getItemType() {
		return VirtualImage.class
	}


	@Override
	Observable list(DatasetQuery datasetQuery) {
		Long cloudId = datasetQuery.get("zoneId")?.toLong()
		Cloud tmpZone = cloudId ? morpheus.services.cloud.get(cloudId) : null
		List<String> supportedImageTypes = getImageTypes()
		log.info("query parameters: ${datasetQuery.parameters}")
		DataQuery query  = new DatasetQuery().withFilters(
			new DataOrFilter(
				new DataFilter("visibility", "public"),
				new DataFilter("accounts.id", datasetQuery.get("accountId")?.toLong()),
				new DataFilter("owner.id", datasetQuery.get("accountId")?.toLong())
			),
			new DataFilter("deleted", false),
			new DataOrFilter(
				new DataFilter("imageType", "in", supportedImageTypes),
				new DataFilter("virtualImageType.code", "in", supportedImageTypes),
			)
		)
		if(tmpZone) {
			query = query.withFilters(
				new DataOrFilter(
					new DataFilter("category", "xenserver.image.${tmpZone.id}"),
					new DataFilter("userUploaded", true)
				)
			)
		}
		query = query.withSort("name", DataQuery.SortOrder.asc)

		return morpheus.async.virtualImage.list(query)
	}

	@Override
	Observable<Map> listOptions(DatasetQuery datasetQuery) {
		list(datasetQuery).map { VirtualImage item ->
			return [name: "${item.name} (${item.imageType}, ${item.id})", value: item.id]
		}
	}

	@Override
	VirtualImage fetchItem(Object value) {
		def rtn = null
		if(value instanceof Long) {
			rtn = item((Long) value)
		} else if(value instanceof CharSequence) {
			def longValue = MorpheusUtils.parseLongConfig(value)
			if(longValue) {
				rtn = item(longValue)
			}
		}
		return rtn
	}

	@Override
	VirtualImage item(Long value) {
		return morpheus.services.virtualImage.get(value)
	}

	@Override
	String itemName(VirtualImage item) {
		return item.name
	}

	@Override
	Long itemValue(VirtualImage item) {
		return item.id
	}

	List<String> getImageTypes() {
		return (List<String>) plugin.getProvidersByType(ProvisionProvider).collect { ProvisionProvider provider ->
			provider.getVirtualImageTypes().collect { it.code }
		}.flatten().unique()
	}
}
