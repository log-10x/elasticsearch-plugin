package com.log10x.l1es.util;

import org.elasticsearch.core.Nullable;

import com.fasterxml.jackson.core.io.JsonStringEncoder;

/**
 * Utility class for common json related actions.
 */
public class JsonUtil {

	/**
	 * Takes an in a {@link String} and quotes it in a json compatible manner.
	 * 
	 * If {@code value} is {@code null} will return {@code "null"}.
	 * 
	 * @param value The {@link String} to be quoted.
	 * @return The resulting quoted {@link String}
	 */
	public static String jsonQuoteString(@Nullable String value) {
		if (value == null) {
			return "null";
		}

		StringBuilder builder = new StringBuilder();

		JsonStringEncoder.getInstance().quoteAsString(value, builder);

		return builder.toString();
	}
}
