package org.elasticsearch.plugin.log10x.config;

import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.settings.Setting.Validator;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;

/**
 * Utility class for common config related actions.
 */
public class ConfigUtil {
	private static final Logger logger = LogManager.getLogger(ConfigUtil.class);

	/**
	 * Creates a {@code Validator<String>} for the provided {@code fieldName}, which
	 * validates that the provided value is either {@code null} or a {@link String}
	 * containing exactly one character.
	 * 
	 * The resulting {@code Validator<String>} throws an
	 * {@link IllegalArgumentException} if validation fails.
	 * 
	 * @param fieldName Name of the field to validate, used in the internal
	 *                  validator exception message.
	 * 
	 * @return A new {@code Validator<String>}
	 */
	public static Validator<String> singleCharacterValidator(final String fieldName) {
		return (v) -> {
			if ((v != null) && (v.length() != 1)) {
				throw new IllegalArgumentException(fieldName + " must be a single character: \"" + v + "\"");
			}
		};
	}

	/**
	 * Returns a new {@link Settings} object loaded from the provided
	 * {@link Environment}.
	 * 
	 * The {@link Settings} object will be based on the file
	 * {@code l1es-plugin/l1es.yml}
	 * 
	 * @param environment Environment to load config from
	 * 
	 * @return {@link Settings} based on the config, or {@code null} in case of
	 *         failures.
	 */
	public static Settings get(final Environment environment) {
		try {
			// Elasticsearch config directory
			//
			final Path configDir = environment.configFile();

			// Resolve the plugin's custom settings file
			//
			final Path settingsYamlFile = configDir.resolve("l1es-plugin/l1es.yml");

			// Load the settings from the plugin's custom settings file
			//
			return Settings.builder().loadFromPath(settingsYamlFile).build();
		} catch (Exception e) {
			logger.error("Failed getting settings.", e);
			return null;
		}
	}
}
