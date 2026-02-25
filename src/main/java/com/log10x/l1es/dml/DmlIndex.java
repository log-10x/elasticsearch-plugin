package com.log10x.l1es.dml;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentParseException;

import org.elasticsearch.xcontent.XContentParser;

/**
 * A POJO mapping for a document stored in
 * {@link DmlConsts#L1ES_DML_INDICES_INDEX_NAME} index.
 * 
 * For mapping info see {@link DmlConsts#L1ES_DML_INDICES_INDEX_MAPPING}.
 *
 */
public class DmlIndex {
	private static final ParseField AFFECTED_INDEX_NAME_FIELD = new ParseField("index_name");
	private static final ParseField SOURCE_FIELD_FIELD = new ParseField("source");
	private static final ParseField DEST_FIELD_FIELD = new ParseField("dest");

	/**
	 * The ES index using this DML index to decode. Also the key in ES.
	 */
	public final String affectedIndexName;

	/**
	 * Name of the encoded field in affectedIndexName.
	 */
	public final String sourceField;

	/**
	 * Name of the target decoded field in affectedIndexName.
	 */
	public final String destField;

	/**
	 * @param affectedIndexName affected index name.
	 * @param sourceField       source field.
	 * @param destField         dest field.
	 */
	public DmlIndex(String affectedIndexName, String sourceField, String destField) {
		this.affectedIndexName = affectedIndexName;
		this.sourceField = sourceField;
		this.destField = destField;
	}

	/**
	 * Returns a source map representation of this to be stored in
	 * {@link DmlConsts#L1ES_DML_INDICES_INDEX_NAME} index.
	 * 
	 * For mapping info see {@link DmlConsts#L1ES_DML_INDICES_INDEX_MAPPING}.
	 * 
	 * @return A source map representation of this.
	 */
	public Map<String, String> sourceMap() {
		Map<String, String> result = new HashMap<>();

		result.put(DmlConsts.L1ES_DML_INDICES_AFFECTED_INDEX_ATTRIBUTE, affectedIndexName);
		result.put(DmlConsts.L1ES_DML_INDICES_SOURCE_FIELD_ATTRIBUTE, sourceField);
		result.put(DmlConsts.L1ES_DML_INDICES_DEST_FIELD_ATTRIBUTE, destField);

		return result;
	}

	/**
	 * Parses a {@link DmlIndex} from the given {@link XContentParser}
	 * 
	 * @param parser              The parser to read the data from.
	 * @param mustHaveFields      If set to {@code true}, will throw a
	 *                            {@link XContentParseException} if no
	 *                            {@link #sourceField} was parsed.
	 * @param checkTrailingTokens If set to {@code true}, expects the content in
	 *                            {@code parser} will end once reading the
	 *                            {@link DmlIndex} is finished. Will throw a
	 *                            {@link XContentParseException} otherwise.
	 * 
	 * @return A new {@link DmlIndex} object parsed from {@code parser}.
	 * 
	 * @throws IOException In case of parsing errors, missing fields, or if the
	 *                     {@code parser} contains more tokens while
	 *                     {@code checkTrailingTokens} is set to {@code true}
	 */
	public static DmlIndex parseXContent(XContentParser parser, boolean mustHaveFields, boolean checkTrailingTokens)
			throws IOException {
		XContentParser.Token token = parser.currentToken();

		String currentFieldName = null;

		if ((token != XContentParser.Token.START_OBJECT)
				&& ((token = parser.nextToken()) != XContentParser.Token.START_OBJECT)) {

			throw new XContentParseException(parser.getTokenLocation(),
					"Expected [" + XContentParser.Token.START_OBJECT + "] but found [" + token + "]");
		}

		String affectedIndexName = null;
		String sourceField = null;
		String destField = null;

		while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
			if (token == XContentParser.Token.FIELD_NAME) {
				currentFieldName = parser.currentName();
			} else if (token.isValue()) {
				if (AFFECTED_INDEX_NAME_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					affectedIndexName = parser.text();
				} else if (SOURCE_FIELD_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					sourceField = parser.text();
				} else if (DEST_FIELD_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
					destField = parser.text();
				} else {
					throw new XContentParseException(parser.getTokenLocation(),
							"Unknown key for a " + token + " in [" + currentFieldName + "].");
				}
			} else {
				throw new XContentParseException(parser.getTokenLocation(),
						"Unknown key for a " + token + " in [" + currentFieldName + "].");
			}
		}

		if (checkTrailingTokens) {
			token = parser.nextToken();

			if (token != null) {
				throw new XContentParseException(parser.getTokenLocation(),
						"Unexpected token [" + token + "] found after the main DmlIndex object.");
			}
		}

		if ((affectedIndexName == null || affectedIndexName.isEmpty())) {
			throw new XContentParseException(parser.getTokenLocation(),
					"Failed to parse DmlIndex: missing 'affectedIndexName'");
		}

		if ((mustHaveFields) && ((sourceField == null || sourceField.isEmpty()))) {
			throw new XContentParseException(parser.getTokenLocation(), "Failed to parse DmlIndex: missing 'sourceField'");
		}

		if ((destField == null || destField.isEmpty())) {
			destField = sourceField;
		}

		return new DmlIndex(affectedIndexName, sourceField, destField);
	}
}
