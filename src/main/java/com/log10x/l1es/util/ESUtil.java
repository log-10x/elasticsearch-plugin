package com.log10x.l1es.util;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.transport.Transports;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * Utility class for common Elasticsearch related actions.
 */
public class ESUtil {
	private static final Logger logger = LogManager.getLogger(ESUtil.class);

	private static final String ES_SOURCE = "_source";
	private static final XContentType ES_SOURCE_CONTEXT_TYPE = XContentType.JSON;
	private static final JsonFactory JSON_FACTORY = new JsonFactory();

	/**
	 * Safely reads a {@link Document} from a given {@link IndexReader}
	 *
	 * @param docID  ID of the {@link Document} to read.
	 * @param reader {@link IndexReader} to read the document from.
	 * @return The {@link Document} read, or {@code null} in case of an error.
	 */
	public static Document safeDocument(int docID, IndexReader reader) {
		try {
			return reader.document(docID);
		} catch (Exception e) {
			logger.error("Failed getting document {}.", docID, e);

			return null;
		}
	}

	/**
	 * Safely returns a {@link Map} representing the source of a given
	 * {@link Document}
	 *
	 * @param doc        The {@link Document} to extract the source from.
	 * @param identifier A {@link String} logging identifier to be emitted in case
	 *                   of failure.
	 *
	 * @return A {@link Map} representation of the given {@link Document} source, or
	 *         {@code null} in case of failure.
	 */
	public static Map<String, Object> getDocumentSource(Document doc, String identifier) {
		// This is here so we can easily change from null to empty map without
		// forgetting some of the cases.
		//
		Map<String, Object> defaultValue = null;

		try {
			BytesRef sourceRef = doc.getBinaryValue(ES_SOURCE);

			if (sourceRef == null) {
				logger.warn("Missing source ref for {}.", identifier);
				return defaultValue;
			}

			BytesReference sourceBytes = new BytesArray(sourceRef);

			Map<String, Object> map = XContentHelper.convertToMap(sourceBytes, false, ES_SOURCE_CONTEXT_TYPE).v2();

			if (map == null) {
				logger.warn("Failed building map from source ref for {}.", identifier);
				return defaultValue;
			}

			return map;
		} catch (Exception e) {
			logger.error("Failed getting document source for {}.", identifier, e);
			return defaultValue;
		}
	}

	/**
	 * Safely getting a field {@code fieldName} from a given {@link Document}
	 */
	public static Object getDocumentField(Document doc, String fieldName, String identifier) {
		Map<String, Object> source = getDocumentSource(doc, identifier);

		if (source == null) {
			return null;
		}

		if (!source.containsKey(fieldName)) {
			logger.warn("No field {} found on doc {}.", fieldName, identifier);
			return null;
		}

		return source.get(fieldName);
	}

	/**
	 * Extracts a single field value from a {@link Document}'s {@code _source}
	 * using a Jackson streaming parser. Avoids allocating a full {@link Map} for
	 * the entire source — only reads until the target field is found.
	 *
	 * @param doc        The {@link Document} to extract the field from.
	 * @param fieldName  The field name to extract.
	 * @param identifier A {@link String} logging identifier for error messages.
	 *
	 * @return The field value as a {@link String}, or {@code null} if not found
	 *         or on error.
	 */
	public static Object getDocumentFieldStreaming(Document doc, String fieldName, String identifier) {
		try {
			BytesRef sourceRef = doc.getBinaryValue(ES_SOURCE);

			if (sourceRef == null) {
				logger.warn("Missing source ref for {}.", identifier);
				return null;
			}

			try (JsonParser parser = JSON_FACTORY.createParser(sourceRef.bytes, sourceRef.offset, sourceRef.length)) {
				if (parser.nextToken() != JsonToken.START_OBJECT) {
					return null;
				}

				while (parser.nextToken() != JsonToken.END_OBJECT) {
					String name = parser.currentName();
					parser.nextToken();

					if (fieldName.equals(name)) {
						return parser.getText();
					}

					parser.skipChildren();
				}
			}

			return null;
		} catch (Exception e) {
			logger.error("Failed streaming field {} for {}.", fieldName, identifier, e);
			return null;
		}
	}

	/**
	 * Checks whether a specific Elasticsearch index exists.
	 */
	public static boolean doesIndexExist(Client client, String indexName) {
		try {
			if (!canPerformSyncActions()) {
				logger.warn("Can't sync validate index {}.", indexName);
				return false;
			}

			client.admin().indices().prepareGetSettings(indexName).get();
			return true;
		} catch (IndexNotFoundException e) {
			return false;
		} catch (Exception e) {
			logger.error("Failed checking index {} existence.", indexName, e);
			return false;
		}
	}

	/**
	 * Checks whether a given {@link Exception} is either an
	 * {@link IndexNotFoundException} or is caused by an
	 * {@link IndexNotFoundException}
	 */
	public static boolean indexMissingException(Exception e) {
		return ((e instanceof IndexNotFoundException) || (e.getCause() instanceof IndexNotFoundException));
	}

	/**
	 * Checks whether Elasticsearch actions can be performed in a sync manner on the
	 * current thread.
	 */
	public static boolean canPerformSyncActions() {
		return !Transports.isTransportThread(Thread.currentThread());
	}
}
