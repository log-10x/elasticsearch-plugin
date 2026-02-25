package com.log10x.l1es.query.match.query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.search.MatchQueryParser;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import com.log10x.l1es.analysis.AnalysisUtil;
import com.log10x.l1es.analysis.AnalyzerTokenizer;
import com.log10x.l1es.dml.DmlDB;
import com.log10x.l1es.dml.DmlUtil;
import com.log10x.l1es.query.predicate.BooleanAndPredicate;
import com.log10x.l1es.query.predicate.PhrasePredicate;
import com.log10x.l1es.query.wrap.L1esQueryWrapper;
import com.log10x.l1es.util.HashDecoder;
import com.log10x.l1es.util.L1esConsts;

/**
 * Class for creating {@link Query}s when specifically querying fields which are
 * L1x encoded.
 */
public class L1esMatchQuery extends MatchQueryParser {
	private static final Logger logger = LogManager.getLogger(L1esMatchQuery.class);

	/** Max entries in tokenizedPatternCache before evicting a batch. */
	private static final int TOKENIZED_PATTERN_CACHE_MAX = 20_000;

	/** Maximum age of the DML index snapshot before it is rebuilt (5 minutes). */
	private static final long DML_INDEX_TTL_NANOS = 5L * 60 * 1_000_000_000L;

	/**
	 * Global cache of tokenized pattern results, keyed by template hash.
	 * Lock-free reads via ConcurrentHashMap. When the cache exceeds
	 * TOKENIZED_PATTERN_CACHE_MAX, a batch of ~25% entries is evicted
	 * (via weakly-consistent iterator) to avoid both cold-start spikes
	 * and lock contention. Cleared on DML index rebuild.
	 */
	private static final ConcurrentHashMap<String, List<String>> tokenizedPatternCache = new ConcurrentHashMap<>();

	/**
	 * Single atomic snapshot of all DML-derived in-memory index structures.
	 * Published as a single volatile write so reader threads always see
	 * a consistent set of structures.
	 */
	private static volatile DmlIndexSnapshot dmlSnapshot;
	private static final Object dmlSnapshotLock = new Object();

	/**
	 * Per-index cached set of l1x_tid values that actually exist in the ES index.
	 * Keyed by index name and reader version to detect staleness on segment
	 * refresh or when a different index is queried.
	 */
	private static volatile IndexedTidsSnapshot indexedTidsSnapshot;
	private static final Object indexedTidsLock = new Object();

	protected final Client client;
	protected final DmlDB dmlDB;

	public L1esMatchQuery(SearchExecutionContext context, Client client, DmlDB dmlDB) {
		super(context);

		this.client = client;
		this.dmlDB = dmlDB;
	}

	@Override
	public Query parse(Type type, String fieldName, Object value) throws IOException {
		if (!DmlUtil.isL1xEncoded(client, indexName(), fieldName)) {
			logger.debug("Field {} in {} isn't l1x-encoded, creating standard {} query.", fieldName, indexName(), type);
			return super.parse(type, fieldName, value);
		}

		try {
			if (type == Type.BOOLEAN) {
				if (this.occur == Occur.SHOULD) {
					return l1esBooleanOrQuery(fieldName, value);
				} else if (this.occur == Occur.MUST) {
					return l1esBooleanAndQuery(fieldName, value);
				}

				logger.warn("Unexpected boolean occur {} when querying for {} ({}).", this.occur, fieldName,
						indexName());
			}

			if (type == Type.PHRASE) {
				return l1esPhraseQuery(fieldName, value);
			}
		} catch (Exception e) {
			logger.error("Failed building custom {} l1es query for {} ({}).", type, fieldName, indexName(), e);
		}

		logger.info("No l1es support for encoded field {} in {}, creating standard {} query.", fieldName, indexName(),
				type);

		return super.parse(type, fieldName, value);
	}

	private Query l1esBooleanOrQuery(String fieldName, Object value) throws IOException {
		Query baseQuery = super.parse(Type.BOOLEAN, fieldName, value);

		Analyzer analyzer = getAnalyzer(Type.BOOLEAN, fieldName);
		AnalyzerTokenizer analyzerTokenizer = AnalyzerTokenizer.of(analyzer, fieldName);
		Set<String> originalValueTokens = analyzerTokenizer.tokenSet(value.toString());

		Collection<String> dmlTokens;
		DmlIndexSnapshot snap = hasTidField() ? getDmlSnapshot(analyzerTokenizer) : null;
		if (snap != null) {
			// Path A: in-memory lookup — no ES round-trip.
			dmlTokens = findOrMatchingHashes(
					(originalValueTokens != null) ? originalValueTokens : Set.of(), snap);
		} else {
			List<String> dmlTokenList = new ArrayList<>();
			QueryBuilder matchQuery = DmlUtil.dmlMatchOrQuery(value.toString());
			dmlDB.searchKeys(matchQuery, dmlTokenList);
			dmlTokens = dmlTokenList;
		}

		if (dmlTokens.isEmpty()) {
			return baseQuery;
		}

		Predicate<String> crossChecker = new BooleanAndPredicate(analyzerTokenizer,
				(originalValueTokens != null) ? originalValueTokens : Set.of());

		Map<String, Boolean> templateMustMatch;
		if (snap != null) {
			templateMustMatch = createLazyBooleanClassification(snap,
					(originalValueTokens != null) ? originalValueTokens : Set.of());
		} else {
			dmlDB.loadPatterns(dmlTokens);
			templateMustMatch = classifyBooleanTemplates(dmlTokens, analyzerTokenizer,
					(originalValueTokens != null) ? originalValueTokens : Set.of());
		}

		BooleanQuery.Builder builder = new BooleanQuery.Builder();

		builder.add(new BooleanClause(baseQuery, BooleanClause.Occur.SHOULD));

		addSplitQueries(builder, fieldName, dmlTokens, templateMustMatch, crossChecker, analyzerTokenizer);

		return builder.build();
	}

	private Query l1esBooleanAndQuery(String fieldName, Object value) throws IOException {
		Analyzer analyzer = getAnalyzer(Type.BOOLEAN, fieldName);
		AnalyzerTokenizer analyzerTokenizer = AnalyzerTokenizer.of(analyzer, fieldName);

		Set<String> originalValueTokens = analyzerTokenizer.tokenSet(value.toString());

		if ((originalValueTokens == null) || (originalValueTokens.size() <= 1)) {
			return l1esBooleanOrQuery(fieldName, value);
		}

		Query baseQuery = super.parse(Type.BOOLEAN, fieldName, value);

		Predicate<String> crossChecker = new BooleanAndPredicate(analyzerTokenizer, originalValueTokens);

		BooleanQuery.Builder builder = new BooleanQuery.Builder();

		builder.add(new BooleanClause(baseQuery, BooleanClause.Occur.SHOULD));

		Set<String> allDmlHashes;
		DmlIndexSnapshot snap = hasTidField() ? getDmlSnapshot(analyzerTokenizer) : null;

		if (snap != null) {
			// Path A: in-memory lookup — no ES round-trip.
			allDmlHashes = findOrMatchingHashes(originalValueTokens, snap);
		} else {
			// Path B: AND search + OR search.
			List<String> exactMatchDmlHashes = new ArrayList<>();
			QueryBuilder matchAndQuery = DmlUtil.dmlMatchAndQuery(value.toString());
			dmlDB.searchKeys(matchAndQuery, exactMatchDmlHashes);

			Set<String> possibleMatchDmlHashes = new HashSet<>();
			QueryBuilder matchQuery = DmlUtil.dmlMatchOrQuery(value.toString());
			dmlDB.searchKeys(matchQuery, possibleMatchDmlHashes);

			possibleMatchDmlHashes.removeAll(exactMatchDmlHashes);

			allDmlHashes = new HashSet<>(exactMatchDmlHashes);
			allDmlHashes.addAll(possibleMatchDmlHashes);
		}

		if (!allDmlHashes.isEmpty()) {
			Map<String, Boolean> templateMustMatch;
			if (snap != null) {
				templateMustMatch = createLazyBooleanClassification(snap, originalValueTokens);

				// Path A AND: classify upfront and use filtered MIGHT_MATCH.
				Set<String> mustMatchHashes = new HashSet<>();
				Set<String> mightMatchHashes = new HashSet<>();

				for (String hash : allDmlHashes) {
					Boolean classification = templateMustMatch.get(hash);
					if (Boolean.TRUE.equals(classification)) {
						mustMatchHashes.add(hash);
					} else if (!Boolean.FALSE.equals(classification)) {
						mightMatchHashes.add(hash);
					}
				}

				if (!mustMatchHashes.isEmpty()) {
					Query tidQuery = createTidTermInSetQuery(mustMatchHashes);
					builder.add(new BooleanClause(tidQuery, BooleanClause.Occur.SHOULD));
				}

				if (!mightMatchHashes.isEmpty()) {
					addFilteredMightMatchQueries(builder, fieldName, mightMatchHashes,
							originalValueTokens, templateMustMatch, crossChecker);
				}
			} else {
				dmlDB.loadPatterns(allDmlHashes);
				templateMustMatch = classifyBooleanTemplates(allDmlHashes, analyzerTokenizer, originalValueTokens);
				addSplitQueries(builder, fieldName, allDmlHashes, templateMustMatch, crossChecker, analyzerTokenizer);
			}
		}

		return builder.build();
	}

	private Query l1esPhraseQuery(String fieldName, Object value) throws IOException {
		Analyzer analyzer = getAnalyzer(Type.PHRASE, fieldName);
		AnalyzerTokenizer analyzerTokenizer = AnalyzerTokenizer.of(analyzer, fieldName);

		List<String> originalValueTokens = analyzerTokenizer.tokenList(value.toString());

		if ((originalValueTokens == null) || (originalValueTokens.size() <= 1)) {
			return l1esBooleanOrQuery(fieldName, value);
		}

		PhrasePredicate crossChecker = new PhrasePredicate(analyzerTokenizer, originalValueTokens);
		BooleanQuery.Builder builder = new BooleanQuery.Builder();

		Map<String, Boolean> templateMustMatch = null;
		Set<String> mustMatchHashes = null;

		if (hasTidField()) {
			// Path A: AND intersection for MUST_MATCH + catch-all for the rest.
			DmlIndexSnapshot snap = getDmlSnapshot(analyzerTokenizer);

			// AND intersection: templates with ALL query tokens in static parts.
			Set<String> andHashes = findAndMatchingHashes(originalValueTokens, snap);

			// Check if all query tokens exist in the DML index.
			boolean allTokensInIndex = true;
			for (String token : originalValueTokens) {
				if (!snap.tokenIndex.containsKey(token)) {
					allTokensInIndex = false;
					break;
				}
			}

			// Classify AND candidates using bigram index for MUST, PhrasePredicate for MIGHT.
			mustMatchHashes = findBigramMustHashes(originalValueTokens, andHashes, snap);

			// Only non-MUST AND candidates need PhrasePredicate check for MIGHT.
			Set<String> mightMatchHashes = new HashSet<>();
			for (String hash : andHashes) {
				if (mustMatchHashes.contains(hash)) continue;
				String pattern = dmlDB.getPattern(hash);
				if (pattern == null) continue;
				List<String> patternTokens = tokenizePatternCached(hash, pattern, analyzerTokenizer);
				if (crossChecker.test(patternTokens, true, false)) {
					mightMatchHashes.add(hash);
				}
			}

			// MUST_MATCH: unwrapped TermInSetQuery (no TwoPhaseIterator needed).
			if (!mustMatchHashes.isEmpty()) {
				Query tidQuery = createTidTermInSetQuery(mustMatchHashes);
				builder.add(new BooleanClause(tidQuery, Occur.SHOULD));
			}

			// MIGHT_MATCH: filtered queries with pre-filters on missing tokens.
			if (!mightMatchHashes.isEmpty()) {
				Map<String, Boolean> phraseClassMap = new HashMap<>();
				for (String hash : mustMatchHashes) {
					phraseClassMap.put(hash, Boolean.TRUE);
				}
				for (String hash : andHashes) {
					if (!mustMatchHashes.contains(hash) && !mightMatchHashes.contains(hash)) {
						phraseClassMap.put(hash, Boolean.FALSE);
					}
				}

				Set<String> queryTokenSet = new HashSet<>(originalValueTokens);
				addFilteredMightMatchQueries(builder, fieldName, mightMatchHashes,
						queryTokenSet, phraseClassMap, crossChecker);
			}

			if (!allTokensInIndex) {
				// Some tokens not in index — need catch-all for correctness.
				templateMustMatch = createLazyTwoPassPhraseClassification(analyzerTokenizer, crossChecker);
				for (String hash : mustMatchHashes) {
					templateMustMatch.put(hash, Boolean.TRUE);
				}
				for (String hash : andHashes) {
					if (!mustMatchHashes.contains(hash)) {
						templateMustMatch.put(hash, Boolean.FALSE);
					}
				}

				Query variablesPhraseQuery = super.parse(Type.PHRASE, fieldName, value);
				Query wrappedCatchAll = L1esQueryWrapper.wrap(variablesPhraseQuery, fieldName, dmlDB,
						crossChecker, templateMustMatch, L1esConsts.L1X_TID_FIELD);
				builder.add(new BooleanClause(wrappedCatchAll, BooleanClause.Occur.SHOULD));
			}
		} else {
			// Path B: phrase search + OR search + filtering.
			List<String> exactPhraseDmlHashes = new ArrayList<>();

			QueryBuilder phraseQuery = DmlUtil.dmlMatchPhraseQuery(value.toString());
			dmlDB.searchKeys(phraseQuery, exactPhraseDmlHashes);

			Map<String, String> possibleMatchDmlEntries = new HashMap<>();

			QueryBuilder matchQuery = DmlUtil.dmlMatchOrQuery(value.toString());

			dmlDB.search(matchQuery, possibleMatchDmlEntries);

			for (String exactPhraseDmlHash : exactPhraseDmlHashes) {
				possibleMatchDmlEntries.remove(exactPhraseDmlHash);
			}

			List<String> possibleMatchDmlHashes = new ArrayList<>();

			for (Map.Entry<String, String> entry : possibleMatchDmlEntries.entrySet()) {
				String pattern = entry.getValue();

				List<String> patternTokens = tokenizePatternCached(entry.getKey(), pattern, analyzerTokenizer);

				boolean canPossiblyMatch = crossChecker.test(patternTokens, true, false);

				if (canPossiblyMatch) {
					possibleMatchDmlHashes.add(entry.getKey());
				}
			}

			List<String> allDmlHashes = new ArrayList<>(exactPhraseDmlHashes);
			allDmlHashes.addAll(possibleMatchDmlHashes);

			if (!allDmlHashes.isEmpty()) {
				dmlDB.loadPatterns(allDmlHashes);
				templateMustMatch = classifyPhraseTemplates(allDmlHashes, analyzerTokenizer, crossChecker);
				addSplitQueries(builder, fieldName, allDmlHashes, templateMustMatch, crossChecker, analyzerTokenizer);
			}

			// Path B catch-all: always needed.
			Query variablesPhraseQuery = super.parse(Type.PHRASE, fieldName, value);
			Query wrappedCatchAll = L1esQueryWrapper.wrap(variablesPhraseQuery, fieldName, dmlDB,
					crossChecker, templateMustMatch);
			builder.add(new BooleanClause(wrappedCatchAll, BooleanClause.Occur.SHOULD));
		}

		return builder.build();
	}

	/**
	 * Creates a {@link TermInSetQuery} on the given field, analyzing each
	 * hash through the field's analyzer. All resulting tokens are combined
	 * into a single TermInSetQuery.
	 */
	private Query createTermInSetQuery(String fieldName, Collection<String> hashes,
			AnalyzerTokenizer analyzerTokenizer) {
		List<BytesRef> terms = new ArrayList<>();

		for (String hash : hashes) {
			List<String> tokens = analyzerTokenizer.tokenList(hash);
			for (String token : tokens) {
				terms.add(new BytesRef(token));
			}
		}

		if (terms.isEmpty()) {
			return null;
		}

		return new TermInSetQuery(fieldName, terms);
	}

	/**
	 * Creates a {@link TermInSetQuery} on the {@code l1x_tid} keyword field.
	 * Since {@code l1x_tid} is a keyword field, no analysis is needed — the
	 * raw hash strings are used directly as terms.
	 */
	private Query createTidTermInSetQuery(Collection<String> hashes) {
		Set<String> indexed = getOrBuildIndexedTids();
		List<BytesRef> terms;
		if (indexed != null) {
			terms = new ArrayList<>();
			for (String hash : hashes) {
				if (indexed.contains(hash)) {
					terms.add(new BytesRef(hash));
				}
			}
			logger.debug("TermInSetQuery: {} hashes filtered to {} indexed terms.", hashes.size(), terms.size());
		} else {
			terms = new ArrayList<>(hashes.size());
			for (String hash : hashes) {
				terms.add(new BytesRef(hash));
			}
		}
		if (terms.isEmpty()) {
			// All hashes filtered out — return a query that matches nothing.
			return new TermInSetQuery(L1esConsts.L1X_TID_FIELD, List.of());
		}
		return new TermInSetQuery(L1esConsts.L1X_TID_FIELD, terms);
	}

	/**
	 * Lazily builds the set of l1x_tid values present in the ES index
	 * by reading the terms dictionary from the IndexReader. Cached per-index
	 * and per-reader-version so it rebuilds on segment refresh and doesn't
	 * collide across indices.
	 */
	private Set<String> getOrBuildIndexedTids() {
		String currentIndex = indexName();
		long currentVersion;
		try {
			IndexReader reader = context.searcher().getIndexReader();
			if (reader instanceof DirectoryReader) {
				currentVersion = ((DirectoryReader) reader).getVersion();
			} else {
				// Fallback: use leaf count + maxDoc as a rough version proxy.
				currentVersion = ((long) reader.leaves().size() << 32) | reader.maxDoc();
			}
		} catch (Exception e) {
			return null;
		}

		IndexedTidsSnapshot snap = indexedTidsSnapshot;
		if (snap != null && snap.isValid(currentIndex, currentVersion)) {
			return snap.tids;
		}

		synchronized (indexedTidsLock) {
			snap = indexedTidsSnapshot;
			if (snap != null && snap.isValid(currentIndex, currentVersion)) {
				return snap.tids;
			}

			try {
				IndexReader reader = context.searcher().getIndexReader();
				Set<String> tids = new HashSet<>();
				for (LeafReaderContext leaf : reader.leaves()) {
					Terms terms = leaf.reader().terms(L1esConsts.L1X_TID_FIELD);
					if (terms == null) continue;
					TermsEnum iter = terms.iterator();
					BytesRef term;
					while ((term = iter.next()) != null) {
						tids.add(term.utf8ToString());
					}
				}
				logger.info("Built indexed tids cache for [{}] (reader v{}): {} unique l1x_tid values.",
						currentIndex, currentVersion, tids.size());
				indexedTidsSnapshot = new IndexedTidsSnapshot(currentIndex, currentVersion, tids);
				return tids;
			} catch (Exception e) {
				logger.warn("Failed building indexed tids cache, falling back to unfiltered.", e);
				return null;
			}
		}
	}

	/**
	 * Checks whether the current index has an {@code l1x_tid} keyword field.
	 */
	private boolean hasTidField() {
		return context.getFieldType(L1esConsts.L1X_TID_FIELD) != null;
	}

	/**
	 * Tokenizes a template pattern with caching. On cache hit, returns the
	 * previously computed token list in O(1). On miss, delegates to
	 * {@link AnalysisUtil#tokenizePattern} and stores the result.
	 */
	private List<String> tokenizePatternCached(String hash, String pattern,
			AnalyzerTokenizer analyzerTokenizer) {
		List<String> cached = tokenizedPatternCache.get(hash);
		if (cached != null) {
			return cached;
		}

		List<String> tokens = AnalysisUtil.tokenizePattern(pattern, analyzerTokenizer);

		// Batch evict ~25% when over capacity — avoids both clear-all cold starts
		// and per-entry lock contention. ConcurrentHashMap iterators are weakly
		// consistent so this is safe under concurrent access.
		if (tokenizedPatternCache.size() >= TOKENIZED_PATTERN_CACHE_MAX) {
			int toRemove = TOKENIZED_PATTERN_CACHE_MAX / 4;
			Iterator<String> it = tokenizedPatternCache.keySet().iterator();
			for (int i = 0; i < toRemove && it.hasNext(); i++) {
				it.next();
				it.remove();
			}
		}

		tokenizedPatternCache.put(hash, tokens);
		return tokens;
	}

	/**
	 * Called when the DML configuration is updated (e.g., new templates added).
	 * Invalidates all DML-derived caches so the next query rebuilds them.
	 */
	public static void invalidateDmlCaches() {
		synchronized (dmlSnapshotLock) {
			dmlSnapshot = null;
			dmlSnapshotRebuilding = false;
			tokenizedPatternCache.clear();
			indexedTidsSnapshot = null;
			dmlSnapshotLock.notifyAll();
		}
		logger.info("DML caches invalidated.");
	}

	/** True while a rebuild is in progress — prevents concurrent rebuilds. */
	private static volatile boolean dmlSnapshotRebuilding;

	/**
	 * Returns the current DML index snapshot, building or rebuilding as needed.
	 * Rebuilds on first call, when invalidated, or when the TTL has expired.
	 *
	 * <p>The expensive build (DML search + tokenization) happens outside the
	 * lock so concurrent queries aren't blocked. Only the final atomic swap
	 * is synchronized. If another thread arrives during a rebuild, it uses
	 * the stale snapshot if one exists, or waits for the rebuild to finish.
	 */
	private DmlIndexSnapshot getDmlSnapshot(AnalyzerTokenizer analyzerTokenizer) {
		DmlIndexSnapshot snap = dmlSnapshot;
		if (snap != null && (System.nanoTime() - snap.builtAtNanos) < DML_INDEX_TTL_NANOS) {
			return snap;
		}

		// Check if another thread is already rebuilding.
		// If so, return stale snapshot (acceptable: at worst 5 extra minutes old).
		synchronized (dmlSnapshotLock) {
			snap = dmlSnapshot;
			if (snap != null && (System.nanoTime() - snap.builtAtNanos) < DML_INDEX_TTL_NANOS) {
				return snap;
			}
			if (dmlSnapshotRebuilding) {
				// Another thread is rebuilding — return stale snapshot if available.
				if (snap != null) {
					return snap;
				}
				// No snapshot at all — must wait.
				while (dmlSnapshotRebuilding) {
					try {
						dmlSnapshotLock.wait(1000);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						break;
					}
				}
				snap = dmlSnapshot;
				if (snap != null) {
					return snap;
				}
			}
			dmlSnapshotRebuilding = true;
		}

		// Build outside the lock — no other queries are blocked.
		try {
			snap = buildDmlSnapshot(analyzerTokenizer);
		} finally {
			synchronized (dmlSnapshotLock) {
				if (snap != null) {
					dmlSnapshot = snap;
					// Clear caches that depend on the DML template set.
					tokenizedPatternCache.clear();
					indexedTidsSnapshot = null;
				}
				dmlSnapshotRebuilding = false;
				dmlSnapshotLock.notifyAll();
			}
		}
		return snap;
	}

	/**
	 * Builds a new DML index snapshot. This is the expensive operation
	 * (match_all DML search + tokenizing all templates). Called outside
	 * the lock so concurrent queries aren't blocked.
	 */
	private DmlIndexSnapshot buildDmlSnapshot(AnalyzerTokenizer analyzerTokenizer) {
		Set<String> allHashes = new HashSet<>();
		dmlDB.searchKeys(new MatchAllQueryBuilder(), allHashes);

		Map<String, LongOpenHashSet> tokenIndex = new HashMap<>();
		Long2ObjectOpenHashMap<Set<String>> staticTokensMap = new Long2ObjectOpenHashMap<>();
		LongOpenHashSet hasVariablesSet = new LongOpenHashSet();
		Long2ObjectOpenHashMap<String> reverseMap = new Long2ObjectOpenHashMap<>();
		Map<String, LongOpenHashSet> bigramIndex = new HashMap<>();

		for (String hash : allHashes) {
			String pattern = dmlDB.getPattern(hash);
			if (pattern == null) {
				continue;
			}

			long hashLong = HashDecoder.decode(hash);
			reverseMap.put(hashLong, hash);

			List<String> tokens = tokenizePatternCached(hash, pattern, analyzerTokenizer);
			Set<String> staticTokens = new HashSet<>();
			boolean hasVars = false;
			String prevStaticToken = null;

			for (String token : tokens) {
				if (token.equals(L1esConsts.VARIABLE_TOKEN)) {
					hasVars = true;
					prevStaticToken = null; // Break the bigram chain at variables.
				} else {
					tokenIndex.computeIfAbsent(token, k -> new LongOpenHashSet()).add(hashLong);
					staticTokens.add(token);

					if (prevStaticToken != null) {
						String bigramKey = prevStaticToken + "\0" + token;
						bigramIndex.computeIfAbsent(bigramKey, k -> new LongOpenHashSet()).add(hashLong);
					}
					prevStaticToken = token;
				}
			}

			staticTokensMap.put(hashLong, staticTokens);
			if (hasVars) {
				hasVariablesSet.add(hashLong);
			}
		}

		logger.info("Built DML memory index: {} templates, {} unique tokens, {} unique bigrams.",
				allHashes.size(), tokenIndex.size(), bigramIndex.size());

		return new DmlIndexSnapshot(tokenIndex, staticTokensMap, hasVariablesSet, reverseMap, bigramIndex);
	}

	/**
	 * Finds all template hashes whose tokenized patterns contain any of
	 * the given query tokens. Equivalent to an OR match against the DML
	 * index but performed entirely in memory. Returns string hashes
	 * via the reverse map.
	 */
	private static Set<String> findOrMatchingHashes(Collection<String> queryTokens,
			DmlIndexSnapshot snap) {
		LongOpenHashSet longs = new LongOpenHashSet();
		for (String token : queryTokens) {
			LongOpenHashSet hashes = snap.tokenIndex.get(token);
			if (hashes != null) {
				longs.addAll(hashes);
			}
		}
		Set<String> result = new HashSet<>(longs.size());
		for (long h : longs) {
			String s = snap.hashLongToString.get(h);
			if (s != null) {
				result.add(s);
			}
		}
		return result;
	}

	/**
	 * Finds all template hashes whose tokenized patterns contain ALL of
	 * the given query tokens. Equivalent to an AND match against the DML
	 * index but performed entirely in memory using set intersection.
	 * Returns string hashes via the reverse map.
	 */
	private static Set<String> findAndMatchingHashes(Collection<String> queryTokens,
			DmlIndexSnapshot snap) {
		LongOpenHashSet result = null;

		for (String token : queryTokens) {
			LongOpenHashSet hashes = snap.tokenIndex.get(token);
			if (hashes == null || hashes.isEmpty()) {
				return new HashSet<>();
			}
			if (result == null) {
				result = new LongOpenHashSet(hashes);
			} else {
				result.retainAll(hashes);
				if (result.isEmpty()) {
					return new HashSet<>();
				}
			}
		}

		if (result == null) {
			return new HashSet<>();
		}

		Set<String> stringResult = new HashSet<>(result.size());
		for (long h : result) {
			String s = snap.hashLongToString.get(h);
			if (s != null) {
				stringResult.add(s);
			}
		}
		return stringResult;
	}

	/**
	 * Finds templates where the phrase tokens appear as a contiguous
	 * subsequence in the static part. Uses the bigram index for O(k)
	 * set intersections instead of O(n) per-template PhrasePredicate calls.
	 *
	 * @param phraseTokens ordered phrase tokens (at least 2)
	 * @param andCandidates AND candidates to intersect with
	 * @param snap DML index snapshot
	 * @return set of template hash strings that are MUST_MATCH
	 */
	private static Set<String> findBigramMustHashes(List<String> phraseTokens, Set<String> andCandidates,
			DmlIndexSnapshot snap) {
		if (phraseTokens.size() < 2) {
			return new HashSet<>();
		}

		LongOpenHashSet result = null;
		for (int i = 0; i < phraseTokens.size() - 1; i++) {
			String bigramKey = phraseTokens.get(i) + "\0" + phraseTokens.get(i + 1);
			LongOpenHashSet hashes = snap.bigramIndex.get(bigramKey);
			if (hashes == null || hashes.isEmpty()) {
				return new HashSet<>();
			}
			if (result == null) {
				result = new LongOpenHashSet(hashes);
			} else {
				result.retainAll(hashes);
				if (result.isEmpty()) {
					return new HashSet<>();
				}
			}
		}

		if (result == null) {
			return new HashSet<>();
		}

		// Intersect with AND candidates (already filtered to templates with all tokens).
		Set<String> stringResult = new HashSet<>();
		for (long h : result) {
			String s = snap.hashLongToString.get(h);
			if (s != null && andCandidates.contains(s)) {
				stringResult.add(s);
			}
		}
		return stringResult;
	}

	/**
	 * Adds template hash queries to the builder. Excludes CANNOT_MATCH
	 * templates entirely.
	 *
	 * <p><b>Path A</b> (l1x_tid field present): Uses exact keyword match
	 * on l1x_tid. MUST_MATCH templates are added unwrapped (no
	 * TwoPhaseIterator). MIGHT_MATCH templates are wrapped.
	 *
	 * <p><b>Path B</b> (no l1x_tid field): Uses {@link TermInSetQuery} on
	 * the analyzed hash tokens. All active templates are wrapped with
	 * TwoPhaseIterator for decode + crossCheck verification.
	 */
	private void addSplitQueries(BooleanQuery.Builder builder, String fieldName,
			Collection<String> dmlHashes, Map<String, Boolean> templateMustMatch,
			Predicate<String> crossChecker, AnalyzerTokenizer analyzerTokenizer) {

		if (dmlHashes.isEmpty()) {
			return;
		}

		// Classify all hashes upfront. With the global tokenization cache,
		// this is near-instant for warm queries and reduces the document space
		// that TwoPhaseIterator must scan.

		Set<String> mustMatchHashes = new HashSet<>();
		Set<String> mightMatchHashes = new HashSet<>();

		for (String hash : dmlHashes) {
			Boolean classification = templateMustMatch.get(hash);

			if (Boolean.TRUE.equals(classification)) {
				mustMatchHashes.add(hash);
			} else if (!Boolean.FALSE.equals(classification)) {
				mightMatchHashes.add(hash); // MIGHT_MATCH
			}
			// CANNOT_MATCH: excluded
		}

		if (mustMatchHashes.isEmpty() && mightMatchHashes.isEmpty()) {
			return;
		}

		if (hasTidField()) {
			// Path A: MUST_MATCH hashes need no TwoPhaseIterator verification.
			if (!mustMatchHashes.isEmpty()) {
				Query tidQuery = createTidTermInSetQuery(mustMatchHashes);
				builder.add(new BooleanClause(tidQuery, BooleanClause.Occur.SHOULD));
			}

			if (!mightMatchHashes.isEmpty()) {
				Query tidQuery = createTidTermInSetQuery(mightMatchHashes);
				Query wrapped = L1esQueryWrapper.wrap(tidQuery, fieldName, dmlDB,
						crossChecker, templateMustMatch, L1esConsts.L1X_TID_FIELD);
				builder.add(new BooleanClause(wrapped, BooleanClause.Occur.SHOULD));
			}
		} else {
			// Path B: all active hashes in a single wrapped query.
			Set<String> activeHashes = new HashSet<>(mustMatchHashes);
			activeHashes.addAll(mightMatchHashes);

			Query hashQuery = createTermInSetQuery(fieldName, activeHashes, analyzerTokenizer);

			if (hashQuery != null) {
				Query wrapped = L1esQueryWrapper.wrap(hashQuery, fieldName, dmlDB,
						crossChecker, templateMustMatch);
				builder.add(new BooleanClause(wrapped, BooleanClause.Occur.SHOULD));
			}
		}
	}

	/**
	 * Adds MIGHT_MATCH queries for AND semantics with missing-token pre-filters.
	 *
	 * <p>For AND queries, tokens missing from a template's static parts must
	 * come from variable values, which are indexed in the message field.
	 * By adding MUST term queries for missing tokens, the inverted index
	 * pre-filters documents, avoiding expensive _source reads for most
	 * MIGHT_MATCH candidates.
	 *
	 * <p>Templates are grouped by their missing token set. Each group gets a
	 * filtered query: {@code l1x_tid IN group AND message:missing1 AND
	 * message:missing2 ...}, wrapped with TwoPhaseIterator.
	 */
	private void addFilteredMightMatchQueries(BooleanQuery.Builder builder, String fieldName,
			Set<String> mightMatchHashes, Set<String> queryTokens,
			Map<String, Boolean> templateMustMatch, Predicate<String> crossChecker) {

		final DmlIndexSnapshot snap = dmlSnapshot;

		// Group MIGHT_MATCH hashes by their missing query tokens.
		Map<Set<String>, List<String>> groups = new HashMap<>();

		for (String hash : mightMatchHashes) {
			long hashLong = HashDecoder.decode(hash);
			Set<String> staticToks = (snap != null) ? snap.staticTokens.get(hashLong) : null;

			Set<String> missing = new HashSet<>(queryTokens);
			if (staticToks != null) {
				missing.removeAll(staticToks);
			}

			if (missing.isEmpty()) {
				// All query tokens in static — shouldn't be MIGHT_MATCH.
				continue;
			}

			groups.computeIfAbsent(missing, k -> new ArrayList<>()).add(hash);
		}

		for (Map.Entry<Set<String>, List<String>> entry : groups.entrySet()) {
			Set<String> missingTokens = entry.getKey();
			List<String> hashes = entry.getValue();

			BooleanQuery.Builder inner = new BooleanQuery.Builder();
			inner.add(new BooleanClause(createTidTermInSetQuery(hashes),
					BooleanClause.Occur.MUST));

			for (String token : missingTokens) {
				inner.add(new BooleanClause(new TermQuery(new Term(fieldName, token)),
						BooleanClause.Occur.MUST));
			}

			Query wrapped = L1esQueryWrapper.wrap(inner.build(), fieldName, dmlDB,
					crossChecker, templateMustMatch, L1esConsts.L1X_TID_FIELD);
			builder.add(new BooleanClause(wrapped, BooleanClause.Occur.SHOULD));
		}
	}

	/**
	 * Creates a lazy classification map for boolean queries. Classifications
	 * are computed on first {@code get()} per template hash, avoiding the
	 * cost of tokenizing all DML-matched templates upfront. Only templates
	 * actually encountered in documents are classified.
	 */
	private Map<String, Boolean> createLazyBooleanClassification(DmlIndexSnapshot snap,
			Set<String> originalValueTokens) {
		return new HashMap<String, Boolean>() {
			private final Set<String> classified = new HashSet<>();

			@Override
			public Boolean get(Object key) {
				String hash = (String) key;
				if (classified.contains(hash)) {
					return super.get(hash);
				}

				classified.add(hash);

				long hashLong = HashDecoder.decode(hash);
				Set<String> staticTokens = snap.staticTokens.get(hashLong);
				boolean hasVars;

				if (staticTokens == null) {
					// Template not in snapshot (rare: new template since snapshot built).
					// Treat as MIGHT_MATCH to be safe.
					return null;
				} else {
					hasVars = snap.hasVariables.contains(hashLong);
				}

				if (staticTokens.containsAll(originalValueTokens)) {
					super.put(hash, Boolean.TRUE);
					return Boolean.TRUE;
				} else if (!hasVars) {
					super.put(hash, Boolean.FALSE);
					return Boolean.FALSE;
				}

				return null; // MIGHT_MATCH
			}
		};
	}

	/**
	 * Creates a lazy two-pass classification map for phrase queries.
	 *
	 * <p>Pass 1: strict phrase check ({@code allowVariableWildcard=false}).
	 * If the phrase appears entirely in the template's static parts (in order),
	 * the template is MUST_MATCH ({@code Boolean.TRUE}).
	 *
	 * <p>Pass 2: wildcard phrase check ({@code allowVariableWildcard=true}).
	 * If the phrase matches when VARIABLE_TOKEN is treated as a wildcard,
	 * the template is MIGHT_MATCH ({@code null}).
	 *
	 * <p>If both checks fail, the template is CANNOT_MATCH ({@code Boolean.FALSE}).
	 */
	private Map<String, Boolean> createLazyTwoPassPhraseClassification(AnalyzerTokenizer analyzerTokenizer,
			PhrasePredicate phrasePredicate) {
		return new HashMap<String, Boolean>() {
			private final Set<String> classified = new HashSet<>();

			@Override
			public Boolean get(Object key) {
				String hash = (String) key;
				if (classified.contains(hash)) {
					return super.get(hash);
				}

				classified.add(hash);

				String pattern = dmlDB.getPattern(hash);
				if (pattern == null) {
					return null;
				}

				List<String> patternTokens = tokenizePatternCached(hash, pattern, analyzerTokenizer);

				// Pass 1: strict — phrase entirely in static parts.
				if (phrasePredicate.test(patternTokens, false, false)) {
					super.put(hash, Boolean.TRUE);
					return Boolean.TRUE;
				}

				// Pass 2: wildcard — phrase could span variable boundaries.
				if (phrasePredicate.test(patternTokens, true, false)) {
					return null; // MIGHT_MATCH
				}

				// Both failed — phrase cannot match this template.
				super.put(hash, Boolean.FALSE);
				return Boolean.FALSE;
			}
		};
	}

	/**
	 * Classifies templates for boolean queries using tri-state logic:
	 * <ul>
	 * <li>{@code Boolean.TRUE} = MUST_MATCH — all search tokens in static parts</li>
	 * <li>{@code Boolean.FALSE} = CANNOT_MATCH — no variables and missing tokens</li>
	 * <li>Not in map = MIGHT_MATCH — has variables, missing tokens could be in variables</li>
	 * </ul>
	 */
	private Map<String, Boolean> classifyBooleanTemplates(Collection<String> dmlHashes,
			AnalyzerTokenizer analyzerTokenizer, Set<String> originalValueTokens) {
		Map<String, Boolean> result = new HashMap<>();
		final DmlIndexSnapshot snap = dmlSnapshot;

		for (String hash : dmlHashes) {
			long hashLong = HashDecoder.decode(hash);
			Set<String> staticTokens = (snap != null) ? snap.staticTokens.get(hashLong) : null;
			boolean hasVars;

			if (staticTokens == null) {
				String pattern = dmlDB.getPattern(hash);
				if (pattern == null) {
					continue;
				}

				List<String> patternTokens = tokenizePatternCached(hash, pattern, analyzerTokenizer);
				hasVars = patternTokens.contains(L1esConsts.VARIABLE_TOKEN);
				staticTokens = new HashSet<>();

				for (String token : patternTokens) {
					if (!token.equals(L1esConsts.VARIABLE_TOKEN)) {
						staticTokens.add(token);
					}
				}
			} else {
				hasVars = snap.hasVariables.contains(hashLong);
			}

			if (staticTokens.containsAll(originalValueTokens)) {
				result.put(hash, Boolean.TRUE);
			} else if (!hasVars) {
				result.put(hash, Boolean.FALSE);
			}
			// else: MIGHT_MATCH — not in map
		}

		return result;
	}

	/**
	 * Classifies templates for phrase queries. A template MUST_MATCH if the
	 * target phrase appears entirely in its static (non-variable) parts.
	 */
	private Map<String, Boolean> classifyPhraseTemplates(Collection<String> dmlHashes,
			AnalyzerTokenizer analyzerTokenizer, PhrasePredicate phrasePredicate) {
		Map<String, Boolean> result = new HashMap<>();

		for (String hash : dmlHashes) {
			String pattern = dmlDB.getPattern(hash);

			if (pattern == null) {
				continue;
			}

			List<String> patternTokens = tokenizePatternCached(hash, pattern, analyzerTokenizer);
			result.put(hash, phrasePredicate.test(patternTokens, false, false));
		}

		return result;
	}

	private Analyzer getAnalyzer(Type type, String fieldName) {
		MappedFieldType fieldType = context.getFieldType(fieldName);

		return getAnalyzer(fieldType, type == Type.PHRASE || type == Type.PHRASE_PREFIX);
	}

	private String indexName() {
		return context.index().getName();
	}
}
