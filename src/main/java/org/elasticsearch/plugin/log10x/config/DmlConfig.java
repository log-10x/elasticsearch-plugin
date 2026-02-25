package org.elasticsearch.plugin.log10x.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;

import com.log10x.l1es.dml.DmlConsts;

/**
 * Config class for Dml related config options.
 */
public class DmlConfig {
	private static final Logger logger = LogManager.getLogger(DmlConfig.class);

	private static final int DEFAULT_INDICES_INDEX_NUMBER_OF_SHARDS = 1;
	private static final int DEFAULT_INDICES_INDEX_NUMBER_OF_REPLICAS = 1;
	private static final int DEFAULT_DML_INDEX_NUMBER_OF_SHARDS = 1;
	private static final int DEFAULT_DML_INDEX_NUMBER_OF_REPLICAS = 1;
	private static final int DEFAULT_DML_SIZE_TO_SEARCH = 10000;

	private static final Setting<Integer> INDICES_INDEX_NUMBER_OF_SHARDS = Setting.intSetting(
			"dmldb.indicesIndexNumberOfShards", DEFAULT_INDICES_INDEX_NUMBER_OF_SHARDS, 1, Property.NodeScope);

	private static final Setting<Integer> INDICES_INDEX_NUMBER_OF_REPLICAS = Setting.intSetting(
			"dmldb.indicesIndexNumberOfReplicas", DEFAULT_INDICES_INDEX_NUMBER_OF_REPLICAS, 1, Property.NodeScope);

	private static final Setting<Integer> DML_INDEX_NUMBER_OF_SHARDS = Setting
			.intSetting("dmldb.dmlIndexNumberOfShards", DEFAULT_DML_INDEX_NUMBER_OF_SHARDS, 1, Property.NodeScope);

	private static final Setting<Integer> DML_INDEX_NUMBER_OF_REPLICAS = Setting
			.intSetting("dmldb.dmlIndexNumberOfReplicas", DEFAULT_DML_INDEX_NUMBER_OF_REPLICAS, 1, Property.NodeScope);

	private static final Setting<Integer> DML_SIZE_TO_SEARCH = Setting.intSetting("dmldb.dmlSizeToSearch",
			DEFAULT_DML_SIZE_TO_SEARCH, 1, 10000, Property.NodeScope);

	/**
	 * Default instance.
	 */
	public static final DmlConfig defaultConfig = new DmlConfig();

	/**
	 * Number of shards to use when creating the
	 * {@link DmlConsts#L1ES_DML_INDICES_INDEX_NAME} index.
	 */
	public final int indicesIndexNumberOfShards;

	/**
	 * Number of replicas to use when creating the
	 * {@link DmlConsts#L1ES_DML_INDICES_INDEX_NAME} index.
	 */
	public final int indicesIndexNumberOfReplicas;

	/**
	 * Number of shards to use when creating the {@link DmlConsts#DML_INDEX_NAME}
	 * index.
	 */
	public final int dmlIndexNumberOfShards;

	/**
	 * Number of replicas to use when creating the {@link DmlConsts#DML_INDEX_NAME}
	 * index.
	 */
	public final int dmlIndexNumberOfReplicas;

	/**
	 * Max size to search when searching in the {@link DmlConsts#DML_INDEX_NAME}
	 * index.
	 */
	public final int dmlSizeToSearch;

	private DmlConfig() {
		this(DEFAULT_INDICES_INDEX_NUMBER_OF_SHARDS, DEFAULT_INDICES_INDEX_NUMBER_OF_REPLICAS,
				DEFAULT_DML_INDEX_NUMBER_OF_SHARDS, DEFAULT_DML_INDEX_NUMBER_OF_REPLICAS, DEFAULT_DML_SIZE_TO_SEARCH);
	}

	private DmlConfig(int indicesIndexNumberOfShards, int indicesIndexNumberOfReplicas, int dmlIndexNumberOfShards,
			int dmlIndexNumberOfReplicas, int dmlSizeToSearch) {

		this.indicesIndexNumberOfShards = indicesIndexNumberOfShards;
		this.indicesIndexNumberOfReplicas = indicesIndexNumberOfReplicas;
		this.dmlIndexNumberOfShards = dmlIndexNumberOfShards;
		this.dmlIndexNumberOfReplicas = dmlIndexNumberOfReplicas;
		this.dmlSizeToSearch = dmlSizeToSearch;
	}

	/**
	 * Returns a new {@link DmlConfig} from the provided {@link Settings}
	 * 
	 * @param settings Settings to build the new {@link DmlConfig} from.
	 * 
	 * @return a new {@link DmlConfig} matching the settings. If {@code settings} is
	 *         {@code null} or an error occurs, will return {@link #defaultConfig}
	 */
	public static DmlConfig get(@Nullable Settings settings) {
		try {
			if (settings == null) {
				logger.warn("Got null settings, falling back to default.");
				return defaultConfig;
			}

			// In the future we may want to allow configuring the index names
			// but that will require stricter validation.
			//
			return new DmlConfig(INDICES_INDEX_NUMBER_OF_SHARDS.get(settings),
					INDICES_INDEX_NUMBER_OF_REPLICAS.get(settings), DML_INDEX_NUMBER_OF_SHARDS.get(settings),
					DML_INDEX_NUMBER_OF_REPLICAS.get(settings), DML_SIZE_TO_SEARCH.get(settings));

		} catch (Exception e) {
			logger.error("Failed getting proper DmlConfig, falling back to default.", e);
			return defaultConfig;
		}
	}
}
