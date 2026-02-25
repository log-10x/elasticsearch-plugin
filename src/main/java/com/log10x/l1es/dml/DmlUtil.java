package com.log10x.l1es.dml;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.index.query.MatchPhraseQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.plugin.log10x.L1esConfig;
import org.elasticsearch.plugin.log10x.config.EncoderConfig;

import com.log10x.decode.SingleEventDecoder;
import com.log10x.decode.lexer.TokenizeContext;
import com.log10x.decode.options.EventEncodeOptions;
import com.log10x.decode.options.TemplateEncodeOptions;
import com.log10x.decode.options.TimestampOptions;
import com.log10x.decode.options.TokenOptions;
import com.log10x.l1es.job.es.document.GetJob;
import com.log10x.l1es.util.ESUtil;

/**
 * Utility class for common Dml related actions.
 */
public class DmlUtil {
	private static final Logger logger = LogManager.getLogger(DmlUtil.class);

	private static volatile TokenizeContext tokenizeContext = TokenizeContext.DEFAULT;

	/**
	 * Persistent cache for {@link DmlIndex} lookups. Maps index name to its
	 * DmlIndex. Eliminates repeated sync ES GET calls for field registration
	 * lookups.
	 */
	private static final ConcurrentHashMap<String, DmlIndex> dmlIndexCache = new ConcurrentHashMap<>();

	/**
	 * Negative cache for indices with no DmlIndex registration.
	 */
	private static final Set<String> negativeDmlIndexCache = ConcurrentHashMap.newKeySet();

	/**
	 * Clears the DmlIndex cache. Should be called when field registrations
	 * change (add/remove dml-index).
	 */
	public static void clearDmlIndexCache() {
		dmlIndexCache.clear();
		negativeDmlIndexCache.clear();
	}

	/**
	 * Gets the {@link DmlIndex} associated with the {@code affectedIndex} in
	 * Elasticsearch.
	 *
	 * Uses an in-memory cache to avoid repeated sync ES GET calls. Cache is
	 * invalidated when field registrations change via
	 * {@link #clearDmlIndexCache()}.
	 *
	 * @param affectedIndex The name of the index to query.
	 * @param client        The {@link Client} which will handle getting the data.
	 *
	 * @return A matching {@code DmlIndex} if one exists, {code null} if it doesn't
	 *         or an error occurs.
	 */
	public static DmlIndex getDmlIndex(String affectedIndex, Client client) {
		// Check positive cache
		DmlIndex cached = dmlIndexCache.get(affectedIndex);

		if (cached != null) {
			return cached;
		}

		// Check negative cache
		if (negativeDmlIndexCache.contains(affectedIndex)) {
			return null;
		}

		if (!ESUtil.doesIndexExist(client, DmlConsts.L1ES_DML_INDICES_INDEX_NAME)) {
			negativeDmlIndexCache.add(affectedIndex);
			return null;
		}

		Predicate<Exception> errorHandler = (e) -> {
			logger.error("Failed getting item {} from l1es dml indices.", affectedIndex, e);

			return true;
		};

		GetJob job = GetJob.create(client, DmlConsts.L1ES_DML_INDICES_INDEX_NAME, affectedIndex);

		GetResponse response = job.get(errorHandler);

		if (response == null) {
			logger.debug("Got null reponse for {}.", affectedIndex);
			return null;
		}

		if (!response.isExists()) {
			logger.debug("No DmlIndex found for {}.", affectedIndex);
			negativeDmlIndexCache.add(affectedIndex);
			return null;
		}

		try {
			Map<String, Object> sourceMap = response.getSource();

			DmlIndex dmlIndex = new DmlIndex(
					(String) sourceMap.get(DmlConsts.L1ES_DML_INDICES_AFFECTED_INDEX_ATTRIBUTE),
					(String) sourceMap.get(DmlConsts.L1ES_DML_INDICES_SOURCE_FIELD_ATTRIBUTE),
					(String) sourceMap.get(DmlConsts.L1ES_DML_INDICES_DEST_FIELD_ATTRIBUTE));

			dmlIndexCache.put(affectedIndex, dmlIndex);
			return dmlIndex;
		} catch (Exception e) {
			logger.error("Failed building DmlIndex for {}.", affectedIndex, e);
			return null;
		}
	}

	/**
	 * Constructs a {@link QueryBuilder} corresponding to match query
	 * ({@link MatchQueryBuilder}), testing {@link DmlConsts#DML_PATTERN_ATTRIBUTE}
	 * against {@code value} with {@link Operator#AND}
	 * 
	 * @param value The value to match.
	 * 
	 * @return The resulting {@code QueryBuilder}
	 */
	public static QueryBuilder dmlMatchAndQuery(String value) {
		return dmlMatchQuery(value, Operator.AND);
	}

	/**
	 * Constructs a {@link QueryBuilder} corresponding to match query
	 * ({@link MatchQueryBuilder}), testing {@link DmlConsts#DML_PATTERN_ATTRIBUTE}
	 * against {@code value} with {@link Operator#OR}
	 * 
	 * @param value The value to match.
	 * 
	 * @return The resulting {@code QueryBuilder}
	 */
	public static QueryBuilder dmlMatchOrQuery(String value) {
		return dmlMatchQuery(value, Operator.OR);
	}

	/**
	 * Constructs a {@link QueryBuilder} corresponding to match query
	 * ({@link MatchQueryBuilder}), testing {@link DmlConsts#DML_PATTERN_ATTRIBUTE}
	 * against {@code value} with the {@link Operator} {@code operator}
	 * 
	 * @param value    The value to match.
	 * @param operator The {@code Operator} to use.
	 * 
	 * @return The resulting {@code QueryBuilder}
	 */
	private static QueryBuilder dmlMatchQuery(String value, Operator operator) {
		return QueryBuilders.matchQuery(DmlConsts.DML_PATTERN_ATTRIBUTE, value).operator(operator);
	}

	/**
	 * Constructs a {@link QueryBuilder} corresponding to match phrase query
	 * ({@link MatchPhraseQueryBuilder}), testing
	 * {@link DmlConsts#DML_PATTERN_ATTRIBUTE} against {@code value}
	 * 
	 * @param value The value to match.
	 * 
	 * @return The resulting {@code QueryBuilder}
	 */
	public static QueryBuilder dmlMatchPhraseQuery(String value) {
		return QueryBuilders.matchPhraseQuery(DmlConsts.DML_PATTERN_ATTRIBUTE, value);
	}

	/**
	 * Extracts a pattern hash id from a given input {@link String} if it exists, or
	 * {@code null} otherwise.
	 * 
	 * A hash is considered existent if {@code text} starts with
	 * {@link EncoderConfig#encodedLinePrefix}, and the hash is then all characters
	 * after that until the first {@link EncoderConfig#valueSeparator} (exclusive).
	 * 
	 * @param text The line to extract the pattern hash id from.
	 * @return Pattern hash id if it exists, {@code null} otherwise.
	 */
	public static String extractPatternId(String text) {
		if (text.isEmpty()) {
			// No text, nothing to do.
			//
			return null;
		}

		EncoderConfig enc = L1esConfig.get().encoder;

		if ((enc.hasEncodedLinePrefix)
				&& (text.charAt(0) != enc.encodedLinePrefix)) {
			// Not encoded, nothing to do.
			//
			return null;
		}

		int firstCharIndex = (enc.hasEncodedLinePrefix ? 1 : 0);

		int index = text.indexOf(enc.valueSeparator);

		if (index == -1) {
			return text.substring(firstCharIndex);
		}

		return text.substring(firstCharIndex, index);
	}

	/**
	 * Wraps a call to {@link #decode(String, DmlDB)} if the provided {@code input}
	 * is {@link String}. Otherwise, simply returns it.
	 * 
	 * @param input Text to decode.
	 * @param dmlDB the {@link DmlDB} to check for the encoded parts.
	 * 
	 * @return The decoded value. In case of errors, returns {@code input}
	 */
	public static Object decode(Object input, DmlDB dmlDB) {
		if (!(input instanceof String)) {
			return input;
		}

		return decode((String) input, dmlDB);
	}

	/**
	 * Decodes a given input that was previously encoded by L1x. Wraps an internal
	 * call to {@link SingleEventDecoder#decode}.
	 * 
	 * @param input Text to decode. This needs to be the full encoded value, with
	 *              the leading hash id. this method will extract it and search for
	 *              it in the provided {@link DmlDB}
	 * 
	 * @param dmlDB the {@link DmlDB} to check for the encoded parts.
	 * 
	 * @return The decoded value. In case of errors, returns {@code input}
	 */
	public static String decode(String input, DmlDB dmlDB) {
		String key = extractPatternId(input);

		if ((key == null || key.isEmpty())) {
			return input;
		}

		String pattern = dmlDB.getPattern(key);

		if ((pattern == null || pattern.isEmpty())) {
			return input;
		}

		int keySize = key.length() + 1; // 1 for L1esPlugin.config.valueSeparator

		if (L1esConfig.get().encoder.hasEncodedLinePrefix) {
			keySize += 1; // 1 for L1esPlugin.config.encodedLinePrefix
		}

		String cleanInput;

		if (keySize > input.length()) {
			// It's possible to have a pattern with no variables.
			//
			cleanInput = "";
		} else {
			cleanInput = input.substring(keySize);
		}

		return decode(key, cleanInput, pattern);
	}

	/**
	 * Decodes a given input that was previously encoded by L1x. Wraps an internal
	 * call to {@link SingleEventDecoder#decode}.
	 * 
	 * @param key     Hash id of {@code pattern}
	 * @param input   Text to decode. This needs to be just the text, i.e. without
	 *                the leading hash id that's usually emitted on the same line by
	 *                L1x.
	 * @param pattern The pattern matching the given {@code key}
	 * 
	 * @return The decoded value. In case of errors, returns {@code input}
	 */
	public static String decode(String key, String input, String pattern) {
		try {
			return SingleEventDecoder.decode(tokenizeContext, key, input, pattern);
		} catch (Exception e) {
			logger.error("Failed decoding for pattern id {}.", key, e);

			return input;
		}
	}

	/**
	 * Checks if a given field {@code fieldName} in the index {@code indexName} is
	 * encoded with L1x.
	 * 
	 * This is done by checking if a {@link DmlIndex} is defined for
	 * {@code indexName}, and if that {@link DmlIndex#sourceField} is equal to
	 * {@code fieldName}.
	 * 
	 * @param client    The {@link Client} which will handle getting the data.
	 * @param indexName Name of the index to check.
	 * @param fieldName Name of the field to check.
	 * 
	 * @return {@code true} if the field {@code fieldName} in the index
	 *         {@code indexName} is encoded by L1x, {@code false} otherwise or in
	 *         case of errors while checking.
	 */
	public static boolean isL1xEncoded(Client client, String indexName, String fieldName) {
		try {
			if (!L1esConfig.get().flags.decodingEnabled()) {
				return false;
			}

			DmlIndex dmlIndex = getDmlIndex(indexName, client);

			if (dmlIndex == null) {
				logger.debug("No need to l1es on index {}.", indexName);
				return false;
			}

			if (!fieldName.equals(dmlIndex.sourceField)) {
				logger.debug("No dml l1es needed for {}/{}.", indexName, fieldName);
				return false;
			}

			logger.debug("Going to l1es on field {}/{}.", indexName, fieldName);

			return true;
		} catch (Exception e) {
			logger.error("Failed checking if dml tokenization needed for {} ({}).", fieldName, indexName);
			return false;
		}
	}

	/**
	 * Updates the l1x decoder config based on updated plugin config.
	 * 
	 * @param config - the {@link EncoderConfig} used for updating the plugin
	 *               config.
	 */
	public static void configUpdated(EncoderConfig config) {
		tokenizeContext = TokenizeContext.create(new TokenOptions(), new TimestampOptions(),
				new TemplateEncodeOptions(), new EventEncodeOptions(config.encodedLinePrefix, config.valueSeparator));
	}
}
