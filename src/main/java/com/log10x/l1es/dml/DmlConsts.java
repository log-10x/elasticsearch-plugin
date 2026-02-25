package com.log10x.l1es.dml;

/**
 * Utility class for Dml related consts.
 */
public class DmlConsts
{
	/**
	 * Name of the L1ES_DML_INDICES index, which contains a mapping from affected L1x
	 * encoded indices to their source/dest encoded fields.
	 */
	public static final String L1ES_DML_INDICES_INDEX_NAME = "l1es_dml_indices";

	/**
	 * Name of the affected index attribute. While the name of the affected index is
	 * also used as the actual id of the document, we also have this here for future
	 * usage if the id will change.
	 */
	public static final String L1ES_DML_INDICES_AFFECTED_INDEX_ATTRIBUTE = "affected_index";
	
	/**
	 * Attribute name for the name of the L1x encoded field in a particular L1x encoded index.
	 */
	public static final String L1ES_DML_INDICES_SOURCE_FIELD_ATTRIBUTE = "source_field";
	
	/**
	 * Attribute name for the name of the field to decode into.
	 */
	public static final String L1ES_DML_INDICES_DEST_FIELD_ATTRIBUTE = "dest_field";
	
	/**
	 * Index field mapping for the {@link L1ES_DML_INDICES_INDEX_NAME} index.
	 */
	public static final String L1ES_DML_INDICES_INDEX_MAPPING = "{\n" +
			"\"dynamic\": \"strict\",\n" +
			"  \"properties\": {\n" +
			"    \"" + L1ES_DML_INDICES_AFFECTED_INDEX_ATTRIBUTE + "\": {\"type\": \"keyword\"},\n" +
			"    \"" + L1ES_DML_INDICES_SOURCE_FIELD_ATTRIBUTE + "\": {\"type\": \"keyword\"},\n" +
			"    \"" + L1ES_DML_INDICES_DEST_FIELD_ATTRIBUTE + "\": {\"type\": \"keyword\"}\n" +
			"  }\n" +
			"}";
	
	/**
	 * Name of the DML index, which holds a "map" from encoded L1x data back to how to decode it.
	 */
	public static final String DML_INDEX_NAME = "l1es_dml";
	
	/**
	 * Attribute name for the name of the field containing the decoded L1x "pattern".
	 */
	public static final String DML_PATTERN_ATTRIBUTE = "pattern";
	
	/**
	 * Index field mapping for the {@link DML_INDEX_NAME} index.
	 */
	public static final String DML_INDEX_MAPPING = "{\n" +
			"  \"dynamic\": false,\n" +
			"  \"properties\": {\n" +
			"    \"" + DML_PATTERN_ATTRIBUTE + "\": {\n" +
			"      \"type\": \"text\"\n" +
			"    }\n" +
			"  }\n" +
			"}";
}
