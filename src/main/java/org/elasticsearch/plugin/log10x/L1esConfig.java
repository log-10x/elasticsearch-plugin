package org.elasticsearch.plugin.log10x;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.plugin.log10x.config.ConfigUtil;
import org.elasticsearch.plugin.log10x.config.DmlConfig;
import org.elasticsearch.plugin.log10x.config.EncoderConfig;
import org.elasticsearch.plugin.log10x.config.FlagsConfig;

import com.log10x.l1es.dml.DmlUtil;
import com.log10x.l1es.query.match.query.L1esMatchQuery;

/**
 * Root config class for the L1es plugin.
 */
public class L1esConfig {
	private static final Logger logger = LogManager.getLogger(L1esConfig.class);

	private static final L1esConfig defaultConfig = new L1esConfig(FlagsConfig.defaultConfig,
			EncoderConfig.defaultConfig, DmlConfig.defaultConfig);

	private static volatile L1esConfig instance;

	/** Max entries in the template cache before evicting a batch. */
	private static final int TEMPLATE_CACHE_MAX = 50_000;

	/**
	 * Node-wide template cache. Maps template hash ID to pattern string.
	 * Lock-free reads via ConcurrentHashMap. When the cache exceeds
	 * TEMPLATE_CACHE_MAX, a batch of ~25% entries is evicted to avoid
	 * both cold-start spikes and lock contention.
	 */
	private static final ConcurrentHashMap<String, String> templateCache = new ConcurrentHashMap<>();

	static {
		instance = L1esConfig.defaultConfig;
	}

	/**
	 * @return the current active {@link L1esConfig} instance.
	 */
	public static L1esConfig get() {
		return instance;
	}

	/**
	 * Gets a cached template pattern by its hash ID.
	 *
	 * @param id Template hash ID.
	 * @return The pattern string, or {@code null} if not cached.
	 */
	public static String getCachedTemplate(String id) {
		return templateCache.get(id);
	}

	/**
	 * Caches a single template pattern.
	 *
	 * @param id      Template hash ID.
	 * @param pattern The pattern string.
	 */
	public static void cacheTemplate(String id, String pattern) {
		if (id != null && pattern != null) {
			evictIfNeeded();
			templateCache.put(id, pattern);
		}
	}

	/**
	 * Caches multiple template patterns at once.
	 *
	 * @param templates Map of template hash IDs to pattern strings.
	 */
	public static void cacheTemplates(Map<String, String> templates) {
		evictIfNeeded();
		templateCache.putAll(templates);
	}

	/**
	 * Evicts ~25% of cache entries when the cache exceeds its capacity.
	 * Uses ConcurrentHashMap's weakly-consistent iterator so no locks
	 * are held during eviction.
	 */
	private static void evictIfNeeded() {
		if (templateCache.size() >= TEMPLATE_CACHE_MAX) {
			int toRemove = TEMPLATE_CACHE_MAX / 4;
			Iterator<String> it = templateCache.keySet().iterator();
			for (int i = 0; i < toRemove && it.hasNext(); i++) {
				it.next();
				it.remove();
			}
		}
	}

	/**
	 * @return The number of templates currently in the global cache.
	 */
	public static int templateCacheSize() {
		return templateCache.size();
	}

	/**
	 * Updates the entire configuration using the provided {@link Environment} to
	 * get the proper settings file.
	 * 
	 * Method is thread-safe, blocking in relation to itself, doing a
	 * {@code synchronized} block on the actual update.
	 * 
	 * @param environment Environment to load config from
	 */
	public static void update(final Environment environment) {
		synchronized (L1esConfig.class) {
			doUpdate(environment);
		}
	}

	/**
	 * Updates the entire configuration using the provided {@link Environment} to
	 * get the proper settings file.
	 * 
	 * Method isn't thread-safe, should only be called from {@link #update}
	 * 
	 * Creates a new {@link #instance} of configuration, as well as calling
	 * {@link DmlUtil#configUpdated}
	 * 
	 * @param environment Environment to load config from
	 */
	private static void doUpdate(final Environment environment) {
		Settings settings = ConfigUtil.get(environment);

		if (settings == null) {
			logger.warn("Got null settings, won't update config.");
		}

		FlagsConfig flags = FlagsConfig.get(settings);
		EncoderConfig encoder = EncoderConfig.get(settings);
		DmlConfig dml = DmlConfig.get(settings);

		L1esConfig newInstance = new L1esConfig(flags, encoder, dml);

		DmlUtil.configUpdated(encoder);
		L1esMatchQuery.invalidateDmlCaches();

		instance = newInstance;
	}

	/**
	 * The {@link FlagsConfig} for this {@link L1esConfig}
	 */
	public final FlagsConfig flags;

	/**
	 * The {@link EncoderConfig} for this {@link L1esConfig}
	 */
	public final EncoderConfig encoder;

	/**
	 * The {@link DmlConfig} for this {@link L1esConfig}
	 */
	public final DmlConfig dml;

	private L1esConfig(FlagsConfig flags, EncoderConfig encoder, DmlConfig dml) {
		this.flags = flags;
		this.encoder = encoder;
		this.dml = dml;
	}
}
