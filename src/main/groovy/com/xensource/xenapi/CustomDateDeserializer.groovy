package com.xensource.xenapi

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer

import java.text.ParseException
import java.text.SimpleDateFormat

/**
 * Patched CustomDateDeserializer that handles empty string values for date fields.
 * XCP-ng returns snapshot_time as "" for non-snapshot VMs, which the SDK's original
 * deserializer cannot handle. This version returns null for null/empty input.
 */
class CustomDateDeserializer extends StdDeserializer<Date> {

	private static final String[] DATE_FORMAT_STRINGS = [
		"yyyyMMdd'T'HHmmss'Z'",
		"yyyy-MM-dd'T'HH:mm:ss'Z'",
		"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
		"yyyyMMdd'T'HH:mm:ss'Z'",
		"yyyyMMdd'T'HH:mm:ss.SSS'Z'",
		"yyyyMMdd'T'HHmmss.SSS'Z'",
		"yyyyMMdd'T'HHmmss.SSS",
		"yyyyMMdd'T'HHmmss",
		"yyyyMMdd'T'HH:mm:ss.SSS",
		"yyyyMMdd'T'HH:mm:ss",
		"yyyy-MM-dd'T'HH:mm:ss.SSS",
		"yyyy-MM-dd'T'HH:mm:ss",
		"yyyyMMdd'T'HHmmss.SSSZZZ",
		"yyyyMMdd'T'HHmmss.SSSZZ",
		"yyyyMMdd'T'HHmmss.SSSZ",
		"yyyyMMdd'T'HHmmss.SSSXXX",
		"yyyyMMdd'T'HHmmss.SSSXX",
		"yyyyMMdd'T'HHmmss.SSSX",
		"yyyyMMdd'T'HHmmssZZZ",
		"yyyyMMdd'T'HHmmssZZ",
		"yyyyMMdd'T'HHmmssZ",
		"yyyyMMdd'T'HHmmssXXX",
		"yyyyMMdd'T'HHmmssXX",
		"yyyyMMdd'T'HHmmssX",
		"yyyyMMdd'T'HH:mm:ss.SSSZZZ",
		"yyyyMMdd'T'HH:mm:ss.SSSZZ",
		"yyyyMMdd'T'HH:mm:ss.SSSZ",
		"yyyyMMdd'T'HH:mm:ss.SSSXXX",
		"yyyyMMdd'T'HH:mm:ss.SSSXX",
		"yyyyMMdd'T'HH:mm:ss.SSSX",
		"yyyyMMdd'T'HH:mm:ssZZZ",
		"yyyyMMdd'T'HH:mm:ssZZ",
		"yyyyMMdd'T'HH:mm:ssZ",
		"yyyyMMdd'T'HH:mm:ssXXX",
		"yyyyMMdd'T'HH:mm:ssXX",
		"yyyyMMdd'T'HH:mm:ssX",
		"yyyy-MM-dd'T'HH:mm:ss.SSSZZZ",
		"yyyy-MM-dd'T'HH:mm:ss.SSSZZ",
		"yyyy-MM-dd'T'HH:mm:ss.SSSZ",
		"yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
		"yyyy-MM-dd'T'HH:mm:ss.SSSXX",
		"yyyy-MM-dd'T'HH:mm:ss.SSSX",
		"yyyy-MM-dd'T'HH:mm:ssZZZ",
		"yyyy-MM-dd'T'HH:mm:ssZZ",
		"yyyy-MM-dd'T'HH:mm:ssZ",
		"yyyy-MM-dd'T'HH:mm:ssXXX",
		"yyyy-MM-dd'T'HH:mm:ssXX",
		"yyyy-MM-dd'T'HH:mm:ssX"
	]

	private static final SimpleDateFormat[] dateFormatsUtc

	static {
		TimeZone utc = TimeZone.getTimeZone("UTC")
		dateFormatsUtc = DATE_FORMAT_STRINGS.collect { pattern ->
			SimpleDateFormat fmt = new SimpleDateFormat(pattern)
			fmt.setTimeZone(utc)
			fmt
		} as SimpleDateFormat[]
	}

	CustomDateDeserializer() {
		super((Class) null)
	}

	CustomDateDeserializer(Class clazz) {
		super(clazz)
	}

	@Override
	Date deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
		String text = p.getText()
		if (text == null || text.isEmpty()) {
			return null
		}
		for (SimpleDateFormat fmt : dateFormatsUtc) {
			try {
				return fmt.parse(text)
			} catch (ParseException ignored) {
				// try next format
			}
		}
		throw new IOException("Failed to deserialize a Date value.")
	}
}
