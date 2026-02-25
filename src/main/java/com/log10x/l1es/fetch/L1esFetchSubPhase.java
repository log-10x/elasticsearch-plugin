package com.log10x.l1es.fetch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.plugin.log10x.L1esConfig;
import org.elasticsearch.search.fetch.FetchContext;
import org.elasticsearch.search.fetch.FetchSubPhase;
import org.elasticsearch.search.fetch.FetchSubPhaseProcessor;
import org.elasticsearch.search.fetch.StoredFieldsSpec;
import org.elasticsearch.search.fetch.subphase.FetchFieldsContext;
import org.elasticsearch.search.fetch.subphase.FieldAndFormat;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;

import com.log10x.l1es.dml.DmlDB;
import com.log10x.l1es.dml.DmlIndex;
import com.log10x.l1es.dml.DmlUtil;

/**
 * Sub phase within the fetch phase used for decoding the document fields that
 * were encoded by l1x.
 *
 * Supports two modes:
 * <ul>
 *   <li><b>Field decoding</b> — decodes values in the {@code fields} response
 *       (requires the encoded field to be in the search request's {@code fields})</li>
 *   <li><b>Source decoding</b> — decodes the encoded field in {@code _source}
 *       for transparent Kibana compatibility (always active for DML-registered
 *       indices when enabled)</li>
 * </ul>
 */
public class L1esFetchSubPhase implements FetchSubPhase {
	private static final Logger logger = LogManager.getLogger(L1esFetchSubPhase.class);

	private volatile Client client;

	public void setClient(Client client) {
		this.client = client;
	}

	@Override
	public FetchSubPhaseProcessor getProcessor(FetchContext fetchContext) throws IOException {
		if (client == null) {
			logger.debug("No client initialized for L1es");
			return null;
		}

		if (!L1esConfig.get().flags.decodingEnabled()) {
			return null;
		}

		String targetIndex = fetchContext.getIndexName();

		DmlIndex dmlIndex = DmlUtil.getDmlIndex(targetIndex, client);

		if (dmlIndex == null) {
			logger.debug("No dml index defined for {}.", targetIndex);
			return null;
		}

		SearchExecutionContext searchContext = fetchContext.getSearchExecutionContext();

		if (!searchContext.isSourceEnabled()) {
			logger.error("Can't expand l1es encoded fields since _source is disabled in the mappings for index [{}]",
					fetchContext.getIndexName());
			return null;
		}

		// Check if the encoded field was requested in 'fields' (for field-level decoding).
		boolean shouldDecodeFields = false;
		FetchFieldsContext fetchFieldsContext = fetchContext.fetchFieldsContext();

		if (fetchFieldsContext != null) {
			for (FieldAndFormat field : fetchFieldsContext.fields()) {
				if (Regex.simpleMatch(field.field, dmlIndex.sourceField, false)) {
					shouldDecodeFields = true;
					break;
				}
			}
		}

		boolean shouldDecodeSource = L1esConfig.get().flags.sourceDecodingEnabled();

		if (!shouldDecodeFields && !shouldDecodeSource) {
			logger.debug("Neither field nor source decoding needed for {}.", targetIndex);
			return null;
		}

		return new L1esFetchSubPhaseProcessor(dmlIndex, DmlDB.of(client), shouldDecodeFields, shouldDecodeSource);
	}

	/**
	 * A {@link FetchSubPhaseProcessor} Responsible for doing the actual decoding of
	 * l1x encoded fields.
	 */
	static class L1esFetchSubPhaseProcessor implements FetchSubPhaseProcessor {
		private final DmlIndex dmlIndex;
		private final DmlDB dmlDB;
		private final boolean shouldDecodeFields;
		private final boolean shouldDecodeSource;

		L1esFetchSubPhaseProcessor(DmlIndex dmlIndex, DmlDB dmlDB, boolean shouldDecodeFields,
				boolean shouldDecodeSource) {
			this.dmlIndex = dmlIndex;
			this.dmlDB = dmlDB;
			this.shouldDecodeFields = shouldDecodeFields;
			this.shouldDecodeSource = shouldDecodeSource;
		}

		@Override
		public void setNextReader(LeafReaderContext readerContext) throws IOException {
			// No per-reader state needed.
		}

		@Override
		public StoredFieldsSpec storedFieldsSpec() {
			return StoredFieldsSpec.NEEDS_SOURCE;
		}

		@Override
		public void process(HitContext hitContext) throws IOException {
			try {
				if (shouldDecodeSource) {
					decodeSource(hitContext, dmlIndex.sourceField);
				}

				if (shouldDecodeFields) {
					decodeField(hitContext, dmlIndex.sourceField, dmlIndex.destField);
				}
			} catch (Exception e) {
				logger.error("Error while decoding field {} in {}.", dmlIndex.sourceField, dmlIndex.affectedIndexName,
						e);
			}
		}

		/**
		 * Decodes the encoded field value in {@code _source} so that Kibana and
		 * other clients see the original decoded text.
		 */
		private void decodeSource(HitContext hitContext, String fieldName) {
			if (!hitContext.hit().hasSource()) {
				return;
			}

			try {
				Map<String, Object> source = hitContext.hit().getSourceAsMap();
				Object encoded = source.get(fieldName);

				if (!(encoded instanceof String)) {
					return;
				}

				String text = (String) encoded;

				if (DmlUtil.extractPatternId(text) == null) {
					// Not an encoded value — no decoding needed.
					return;
				}

				String decoded = DmlUtil.decode(text, dmlDB);

				if (decoded.equals(text)) {
					// Couldn't decode (missing template, etc.)
					return;
				}

				source.put(fieldName, decoded);

				// Rebuild the raw _source bytes so the serialized response has decoded content.
				XContentBuilder builder = XContentFactory.jsonBuilder().map(source);
				hitContext.hit().sourceRef(BytesReference.bytes(builder));
			} catch (Exception e) {
				logger.error("Error decoding _source field {} in {}.", fieldName, dmlIndex.affectedIndexName, e);
			}
		}

		private void decodeField(HitContext hitContext, String source, String dest) {
			DocumentField field = hitContext.hit().field(source);

			if (field == null) {
				logger.debug("Didn't find field {}, won't decode ({}).", source, dmlIndex.affectedIndexName);
				return;
			}

			List<Object> values = new ArrayList<>();

			for (Object o : field.getValues()) {
				Object decoded = DmlUtil.decode(o, dmlDB);
				values.add(decoded);
			}

			if (values.isEmpty()) {
				logger.debug("No decoded values for {} ({}).", source, dmlIndex.affectedIndexName);
				return;
			}

			hitContext.hit().setDocumentField(dest, new DocumentField(dest, values));
		}
	}
}
