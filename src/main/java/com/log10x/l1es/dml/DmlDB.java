package com.log10x.l1es.dml;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.search.Query;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.get.MultiGetItemResponse;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.plugin.log10x.L1esConfig;
import org.elasticsearch.plugin.log10x.config.DmlConfig;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;

import com.log10x.l1es.job.es.SearchJob;
import com.log10x.l1es.job.es.document.GetJob;
import com.log10x.l1es.job.es.document.MultiGetJob;
import com.log10x.l1es.util.ESUtil;

/**
 * Handles search/get operations against the {@link DmlConsts#DML_INDEX_NAME}
 * index in Elasticsearch.
 * 
 * Holds a simple in memory cache of found patterns so future gets are fast.
 * 
 * Because there's no eviction policy of the cache, this class is meant to be
 * used for single "processes", like working on a single user {@link Query}, and
 * not as a static singleton holding data.
 */
public class DmlDB {
	private static final Logger logger = LogManager.getLogger(DmlDB.class);

	private final Client client;
	private final Map<String, String> loadedPatterns;

	private DmlDB(Client client) {
		this.client = client;
		this.loadedPatterns = new HashMap<>();
	}

	/**
	 * Overload for {@link #getPattern(String, boolean)} with a default value of
	 * {@code true} for {@code loadMissing}
	 * 
	 * @param id Id of the pattern to get.
	 * 
	 * @return The pattern matching the given id.
	 */
	public String getPattern(String id) {
		return getPattern(id, true);
	}

	/**
	 * Gets a single pattern from {@link DmlConsts#DML_INDEX_NAME}.
	 * 
	 * Will attempt to get from internal {@link #loadedPatterns} first.
	 * 
	 * If pattern not found in {@link #loadedPatterns} and {@code loadMissing} is
	 * {@code true}, will attempt to load it.
	 * 
	 * @param id          Id of the pattern to get.
	 * @param loadMissing Whether to attempt to load a missing pattern or not
	 * 
	 * @return The pattern matching the given id.
	 */
	public String getPattern(String id, boolean loadMissing) {
		// 1. Check per-query cache
		String result = loadedPatterns.get(id);

		if (result != null) {
			return result;
		}

		// 2. Check global persistent cache
		result = L1esConfig.getCachedTemplate(id);

		if (result != null) {
			loadedPatterns.put(id, result);
			return result;
		}

		// 3. Load from ES
		if (!loadMissing) {
			return null;
		}

		loadPattern(id);

		return loadedPatterns.get(id);
	}

	/**
	 * Loads a single pattern from {@link DmlConsts#DML_INDEX_NAME} index.
	 * 
	 * Runs in a sync manner, so will fail with a log warning if called from a
	 * thread that can't {@link ESUtil#canPerformSyncActions()}
	 * 
	 * Stores loaded pattern in {@link #loadedPatterns}
	 * 
	 * @param id Id of the pattern to load.
	 */
	private void loadPattern(String id) {
		if (!ESUtil.canPerformSyncActions()) {
			logger.warn("Can't sync load pattern {}.", id);

			return;
		}

		Predicate<Exception> errorHandler = (e) -> {
			if (ESUtil.indexMissingException(e)) {
				logger.warn("DML index {} doesn't exist.", DmlConsts.DML_INDEX_NAME);
			} else {
				logger.error("Error getting pattern {} from DML index {}.", id, DmlConsts.DML_INDEX_NAME);
			}

			return true;
		};

		GetJob job = GetJob.create(client, DmlConsts.DML_INDEX_NAME, id);

		GetResponse response = job.get(errorHandler);

		if (response != null) {
			processGetResponse(id, response);
		}
	}

	private void processGetResponse(String id, GetResponse response) {
		if (!response.isExists()) {
			logger.debug("Pattern {} doesn't exist in DML index {}.", id, DmlConsts.DML_INDEX_NAME);
			return;
		}

		try {
			String pattern = (String) response.getSourceAsMap().get(DmlConsts.DML_PATTERN_ATTRIBUTE);

			loadedPatterns.put(id, pattern);
			L1esConfig.cacheTemplate(id, pattern);
		} catch (Exception e) {
			logger.error("Failed processing get response for {}.", id, e);
		}
	}

	/**
	 * Performs a sync search on {@link DmlConsts#DML_INDEX_NAME} index, and returns
	 * the result patterns in a {@code Map<String, String>} mapping each pattern
	 * hash id to the matching pattern.
	 * 
	 * Found patterns are also cached in {@link #loadedPatterns} for quicker
	 * {@link #getPattern} calls later.
	 * 
	 * @param query The query to search.
	 * @return A new {@code HashMap} with the search result.
	 */
	public Map<String, String> search(QueryBuilder query) {
		return search(query, new HashMap<>());
	}

	/**
	 * Performs a sync search on {@link DmlConsts#DML_INDEX_NAME} index, and fills
	 * the provided {@code map} with the result patterns, mapping each pattern hash
	 * id to the matching pattern.
	 * 
	 * Found patterns are also cached in {@link #loadedPatterns} for quicker
	 * {@link #getPattern} calls later.
	 * 
	 * @param query The query to search.
	 * @param map   The {@code Map<String, String>} to fill the results.
	 * 
	 * @return The given {@code map}.
	 */
	public Map<String, String> search(QueryBuilder query, Map<String, String> map) {
		SearchResponse response = internalSearch(query);

		if (response == null) {
			return map;
		}

		SearchHits hits = response.getHits();

		if ((hits != null) && (hits.getTotalHits().value > 0) && (hits.getHits() != null)) {
			for (SearchHit hit : hits.getHits()) {
				fillDmlFromSearchHit(hit, map);
			}
		}

		return map;
	}

	/**
	 * Performs a sync search on {@link DmlConsts#DML_INDEX_NAME} index, and returns
	 * the pattern hash ids in a {@code Collection<String>}.
	 * 
	 * Found patterns are also cached in {@link #loadedPatterns} for quicker
	 * {@link #getPattern} calls later.
	 * 
	 * @param query The query to search.
	 * @return A new {@code HashSet} with the search result.
	 */
	public Collection<String> searchKeys(QueryBuilder query) {
		return searchKeys(query, new HashSet<>());
	}

	/**
	 * Performs a sync search on {@link DmlConsts#DML_INDEX_NAME} index, and fills
	 * the provided {@code collection} with the hash ids of the result patterns.
	 * 
	 * Found patterns are also cached in {@link #loadedPatterns} for quicker
	 * {@link #getPattern} calls later.
	 * 
	 * @param query      The query to search.
	 * @param collection The {@code Collection<String>} to fill the results.
	 * 
	 * @return The given {@code collection}.
	 */
	public Collection<String> searchKeys(QueryBuilder query, Collection<String> collection) {
		SearchResponse response = internalSearch(query);

		if (response == null) {
			return collection;
		}

		SearchHits hits = response.getHits();

		if ((hits != null) && (hits.getTotalHits().value > 0) && (hits.getHits() != null)) {
			for (SearchHit hit : hits.getHits()) {
				fillDmlFromSearchHit(hit, collection);
			}
		}

		return collection;
	}

	/**
	 * Runs a sync search in {@link DmlConsts#DML_INDEX_NAME} with the provided
	 * {@link Query} Search size is capped at {@link DmlConfig#dmlSizeToSearch}.
	 * 
	 * If the current thread can't perform sync operations (verified by
	 * {@link ESUtil#canPerformSyncActions}), returns {@code null}
	 * 
	 * @param query The query to run.
	 * 
	 * @return The result {@link SearchResponse}. In case of an error, or if sync
	 *         operations aren't allowed on current thread, returns {@code null}
	 */
	private SearchResponse internalSearch(QueryBuilder query) {
		if (query == null) {
			return null;
		}

		if (!ESUtil.canPerformSyncActions()) {
			logger.warn("Can't sync search dml.");
			return null;
		}

		Predicate<Exception> errorHandler = (e) -> {
			if (ESUtil.indexMissingException(e)) {
				logger.warn("DML index {} doesn't exist.", DmlConsts.DML_INDEX_NAME);

				return true;
			}

			return false;
		};

		SearchJob job = SearchJob.create(client, DmlConsts.DML_INDEX_NAME, query, L1esConfig.get().dml.dmlSizeToSearch);

		return job.get(errorHandler);
	}

	private void fillDmlFromSearchHit(SearchHit hit, Map<String, String> result) {
		try {
			String id = hit.getId();
			String value = (String) hit.getSourceAsMap().get(DmlConsts.DML_PATTERN_ATTRIBUTE);

			loadedPatterns.put(id, value);
			L1esConfig.cacheTemplate(id, value);
			result.put(id, value);
		} catch (Exception e) {
			logger.error("Failed filling dml from search hit {}.", hit.getId(), e);
		}
	}

	private void fillDmlFromSearchHit(SearchHit hit, Collection<String> result) {
		try {
			String id = hit.getId();
			String value = (String) hit.getSourceAsMap().get(DmlConsts.DML_PATTERN_ATTRIBUTE);

			loadedPatterns.put(id, value);
			L1esConfig.cacheTemplate(id, value);
			result.add(id);
		} catch (Exception e) {
			logger.error("Failed filling dml from search hit {}.", hit.getId(), e);
		}
	}

	/**
	 * Batch-loads multiple patterns from {@link DmlConsts#DML_INDEX_NAME} in a
	 * single MultiGet request. Only loads patterns not already in the per-query
	 * or global cache.
	 *
	 * @param ids Collection of pattern hash IDs to pre-load.
	 */
	public void loadPatterns(Collection<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return;
		}

		// Filter to only IDs not in per-query or global cache
		List<String> missing = ids.stream()
				.filter(id -> !loadedPatterns.containsKey(id))
				.filter(id -> L1esConfig.getCachedTemplate(id) == null)
				.collect(Collectors.toList());

		if (missing.isEmpty()) {
			return;
		}

		if (!ESUtil.canPerformSyncActions()) {
			logger.warn("Can't sync batch load {} patterns.", missing.size());
			return;
		}

		Predicate<Exception> errorHandler = (e) -> {
			if (ESUtil.indexMissingException(e)) {
				logger.warn("DML index {} doesn't exist.", DmlConsts.DML_INDEX_NAME);
			} else {
				logger.error("Error batch loading {} patterns from DML index {}.", missing.size(),
						DmlConsts.DML_INDEX_NAME);
			}

			return true;
		};

		MultiGetJob job = MultiGetJob.create(client, DmlConsts.DML_INDEX_NAME, missing);

		MultiGetResponse response = job.get(errorHandler);

		if (response == null) {
			return;
		}

		for (MultiGetItemResponse item : response.getResponses()) {
			if (item.isFailed()) {
				continue;
			}

			GetResponse getResponse = item.getResponse();

			if (getResponse != null && getResponse.isExists()) {
				try {
					String id = getResponse.getId();
					String pattern = (String) getResponse.getSourceAsMap().get(DmlConsts.DML_PATTERN_ATTRIBUTE);

					loadedPatterns.put(id, pattern);
					L1esConfig.cacheTemplate(id, pattern);
				} catch (Exception e) {
					logger.error("Failed processing batch get response for {}.", item.getId(), e);
				}
			}
		}

		logger.debug("Batch loaded {} patterns ({} were missing).", ids.size(), missing.size());
	}

	/**
	 * Creates a new {@link DmlDB} for a given {@link Client}
	 *
	 * @param client the {@link Client}
	 * @return a new {@link DmlDB}
	 */
	public static DmlDB of(Client client) {
		return new DmlDB(client);
	}
}
