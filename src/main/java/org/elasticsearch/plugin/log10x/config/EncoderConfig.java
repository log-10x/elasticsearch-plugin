package org.elasticsearch.plugin.log10x.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;

/**
 * Config class for Encoder related config options.
 */
public class EncoderConfig {
	private static final Logger logger = LogManager.getLogger(EncoderConfig.class);

	private static final boolean DEFAULT_HAS_ENCODED_LINE_PREFIX = false;
	private static final String DEFAULT_ENCODED_LINE_PREFIX = "~";
	private static final String DEFAULT_VALUE_SEPERATOR = " ";

	private static final Setting<Boolean> HAS_ENCODED_LINE_PREFIX = Setting.boolSetting("encoder.hasEncodedLinePrefix",
			DEFAULT_HAS_ENCODED_LINE_PREFIX, Property.NodeScope);

	private static final Setting<String> ENCODED_LINE_PREFIX = Setting.simpleString("encoder.encodedLinePrefix",
			DEFAULT_ENCODED_LINE_PREFIX, ConfigUtil.singleCharacterValidator("encodedLinePrefix"), Property.NodeScope);

	private static final Setting<String> VALUE_SEPRARATOR = Setting.simpleString("encoder.valueSeperator",
			DEFAULT_VALUE_SEPERATOR, ConfigUtil.singleCharacterValidator("valueSeperator"), Property.NodeScope);

	/**
     * Default instance.
     */
	public static final EncoderConfig defaultConfig = new EncoderConfig();

	
	/**
	 * Defines whether L1x encoded lines are expected to have a prefix character to identify them.
	 */
	public final boolean hasEncodedLinePrefix;
	
	/**
	 * Defines the prefix character identifying L1x encoded lines.
	 */
	public final char encodedLinePrefix;
	
	/**
	 * Defines the separator character used between values in an L1x encoded line.
	 */
	public final char valueSeparator;

	private EncoderConfig() {
		this(DEFAULT_HAS_ENCODED_LINE_PREFIX, DEFAULT_ENCODED_LINE_PREFIX.charAt(0), DEFAULT_VALUE_SEPERATOR.charAt(0));
	}

	private EncoderConfig(boolean hasEncodedLinePrefix, char encodedLinePrefix, char valueSeparator) {
		this.hasEncodedLinePrefix = hasEncodedLinePrefix;
		this.encodedLinePrefix = encodedLinePrefix;
		this.valueSeparator = valueSeparator;
	}

	/**
	 * Returns a new {@link EncoderConfig} from the provided {@link Settings}
	 * 
	 * @param settings Settings to build the new {@link EncoderConfig} from.
	 * 
	 * @return a new {@link EncoderConfig} matching the settings. If
	 *         {@code settings} is {@code null} or an error occurs, will return
	 *         {@link #defaultConfig}
	 */
	public static EncoderConfig get(@Nullable Settings settings) {
		try {
			if (settings == null) {
				logger.warn("Got null settings, falling back to default.");
				return defaultConfig;
			}

			return new EncoderConfig(
					HAS_ENCODED_LINE_PREFIX.get(settings),
					ENCODED_LINE_PREFIX.get(settings).charAt(0),
					VALUE_SEPRARATOR.get(settings).charAt(0));

		} catch (Exception e) {
			logger.error("Failed getting proper EncoderConfig, falling back to default.", e);
			return defaultConfig;
		}
	}
}
