package org.elasticsearch.plugin.log10x.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;

/**
 * Config class for controlling different plugin behavior flags.
 */
public class FlagsConfig {
	private static final Logger logger = LogManager.getLogger(FlagsConfig.class);

	private static final boolean DEFAULT_ENABLED = true;
	private static final boolean DEFAULT_DECODING_ENABLED = true;
	private static final boolean DEFAULT_MATCH_QUERY_ENABLED = true;
	private static final boolean DEFAULT_MATCH_PHRASE_QUERY_ENABLED = true;
	private static final boolean DEFAULT_MULTI_MATCH_QUERY_ENABLED = true;
	private static final boolean DEFAULT_QUERY_REWRITE_ENABLED = true;
	private static final boolean DEFAULT_SOURCE_DECODING_ENABLED = true;

	private static final Setting<Boolean> ENABLED = Setting.boolSetting("flags.enabled", DEFAULT_ENABLED,
			Property.NodeScope);

	private static final Setting<Boolean> DECODING_ENABLED = Setting.boolSetting("flags.decoding_enabled",
			DEFAULT_DECODING_ENABLED, Property.NodeScope);

	private static final Setting<Boolean> MATCH_QUERY_ENABLED = Setting.boolSetting("flags.match_query_enabled",
			DEFAULT_MATCH_QUERY_ENABLED, Property.NodeScope);

	private static final Setting<Boolean> MATCH_PHRASE_QUERY_ENABLED = Setting
			.boolSetting("flags.match_pharse_query_enabled", DEFAULT_MATCH_PHRASE_QUERY_ENABLED, Property.NodeScope);

	private static final Setting<Boolean> MULTI_MATCH_QUERY_ENABLED = Setting
			.boolSetting("flags.multi_match_query_enabled", DEFAULT_MULTI_MATCH_QUERY_ENABLED, Property.NodeScope);

	private static final Setting<Boolean> QUERY_REWRITE_ENABLED = Setting
			.boolSetting("flags.query_rewrite_enabled", DEFAULT_QUERY_REWRITE_ENABLED, Property.NodeScope);

	private static final Setting<Boolean> SOURCE_DECODING_ENABLED = Setting
			.boolSetting("flags.source_decoding_enabled", DEFAULT_SOURCE_DECODING_ENABLED, Property.NodeScope);

	/**
	 * Default instance.
	 */
	public static final FlagsConfig defaultConfig = new FlagsConfig();

	private static final FlagsConfig offConfig = new FlagsConfig(false, false, false, false, false, false, false);

	private final boolean enabled;
	private final boolean decodingEnabled;
	private final boolean matchQueryEnabled;
	private final boolean matchPharseQueryEnabled;
	private final boolean multiMatchQueryEnabled;
	private final boolean queryRewriteEnabled;
	private final boolean sourceDecodingEnabled;

	private FlagsConfig() {
		this(DEFAULT_ENABLED, DEFAULT_DECODING_ENABLED, DEFAULT_MATCH_QUERY_ENABLED, DEFAULT_MATCH_PHRASE_QUERY_ENABLED,
				DEFAULT_MULTI_MATCH_QUERY_ENABLED, DEFAULT_QUERY_REWRITE_ENABLED, DEFAULT_SOURCE_DECODING_ENABLED);
	}

	private FlagsConfig(boolean enabled, boolean decodingEnabled, boolean matchQueryEnabled,
			boolean matchPharseQueryEnabled, boolean multiMatchQueryEnabled, boolean queryRewriteEnabled,
			boolean sourceDecodingEnabled) {
		this.enabled = enabled;
		this.decodingEnabled = decodingEnabled;
		this.matchQueryEnabled = matchQueryEnabled;
		this.matchPharseQueryEnabled = matchPharseQueryEnabled;
		this.multiMatchQueryEnabled = multiMatchQueryEnabled;
		this.queryRewriteEnabled = queryRewriteEnabled;
		this.sourceDecodingEnabled = sourceDecodingEnabled;
	}

	/**
	 * @return true if the plugin config is enabled, and decoding is enabled as well
	 */
	public boolean decodingEnabled() {
		return ((this.enabled) && (this.decodingEnabled));
	}

	/**
	 * @return true if the plugin config is enabled, and utilizing L1es match
	 *         queries is enabled as well.
	 */
	public boolean matchQueryEnabled() {
		return ((this.enabled) && (this.matchQueryEnabled));
	}

	/**
	 * @return true if the plugin config is enabled, and utilizing L1es match phrase
	 *         queries is enabled as well.
	 */
	public boolean matchPharseQueryEnabled() {
		return ((this.enabled) && (this.matchPharseQueryEnabled));
	}

	/**
	 * @return true if the plugin config is enabled, and utilizing L1es multi match
	 *         queries is enabled as well.
	 */
	public boolean multiMatchQueryEnabled() {
		return ((this.enabled) && (this.multiMatchQueryEnabled));
	}

	/**
	 * @return true if the plugin config is enabled, and transparent query
	 *         rewriting is enabled as well.
	 */
	public boolean queryRewriteEnabled() {
		return ((this.enabled) && (this.queryRewriteEnabled));
	}

	/**
	 * @return true if the plugin config is enabled, and _source decoding is
	 *         enabled as well.
	 */
	public boolean sourceDecodingEnabled() {
		return ((this.enabled) && (this.sourceDecodingEnabled));
	}

	/**
	 * Returns a new {@link FlagsConfig} from the provided {@link Settings}
	 * 
	 * If the main flag {@link #ENABLED} will resolve as {@code false}, the
	 * resulting {@link FlagsConfig} will be {@link #offConfig}
	 * 
	 * @param settings Settings to build the new {@link FlagsConfig} from.
	 * 
	 * @return a new {@link FlagsConfig} matching the settings. If {@code settings}
	 *         is {@code null} or an error occurs, will return
	 *         {@link #defaultConfig}
	 */
	public static FlagsConfig get(Settings settings) {
		try {
			if (settings == null) {
				logger.warn("Got null settings, falling back to default.");
				return defaultConfig;
			}

			boolean masterEnabled = ENABLED.get(settings);

			if (!masterEnabled) {
				return offConfig;
			}

			return new FlagsConfig(true, DECODING_ENABLED.get(settings), MATCH_QUERY_ENABLED.get(settings),
					MATCH_PHRASE_QUERY_ENABLED.get(settings), MULTI_MATCH_QUERY_ENABLED.get(settings),
					QUERY_REWRITE_ENABLED.get(settings), SOURCE_DECODING_ENABLED.get(settings));

		} catch (Exception e) {
			logger.error("Failed getting proper FlagsConfig, falling back to default.", e);
			return defaultConfig;
		}
	}
}
