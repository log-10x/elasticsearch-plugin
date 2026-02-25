package com.log10x.l1es.query;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.BoostingQueryBuilder;
import org.elasticsearch.index.query.ConstantScoreQueryBuilder;
import org.elasticsearch.index.query.DisMaxQueryBuilder;
import org.elasticsearch.index.query.MatchPhraseQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;

import com.log10x.l1es.query.match.L1esMatchPhraseQueryBuilder;
import com.log10x.l1es.query.match.L1esMatchQueryBuilder;
import com.log10x.l1es.query.match.L1esMultiMatchQueryBuilder;

/**
 * Recursively rewrites standard Elasticsearch query builders to their L1ES
 * equivalents. This enables transparent Kibana compatibility — standard
 * {@code match}, {@code match_phrase}, and {@code multi_match} queries are
 * converted to {@code l1es_match}, {@code l1es_match_phrase}, and
 * {@code l1es_multi_match} so they can search encoded data.
 *
 * The L1ES query builders already fall back to standard behavior for
 * non-encoded fields, so this rewriting is safe to apply globally.
 */
public class L1esQueryRewriter {
	private static final Logger logger = LogManager.getLogger(L1esQueryRewriter.class);

	/**
	 * Rewrites a query tree, replacing standard match/match_phrase/multi_match
	 * queries with their L1ES equivalents. Returns the original query if no
	 * rewriting was needed.
	 *
	 * @param query  The root query to rewrite.
	 * @param client The ES client (needed by L1ES query builders).
	 * @return The rewritten query, or the original if unchanged.
	 */
	public static QueryBuilder rewrite(QueryBuilder query, Client client) {
		if (query == null || client == null) {
			return query;
		}

		try {
			return doRewrite(query, client);
		} catch (Exception e) {
			logger.error("Error rewriting query, returning original.", e);
			return query;
		}
	}

	private static QueryBuilder doRewrite(QueryBuilder query, Client client) {
		// Already an L1ES query — don't rewrite again.
		if (query instanceof L1esMatchQueryBuilder
				|| query instanceof L1esMatchPhraseQueryBuilder
				|| query instanceof L1esMultiMatchQueryBuilder) {
			return query;
		}

		// match → l1es_match
		if (query instanceof MatchQueryBuilder) {
			return L1esMatchQueryBuilder.fromStandard((MatchQueryBuilder) query, client);
		}

		// match_phrase → l1es_match_phrase
		if (query instanceof MatchPhraseQueryBuilder) {
			return L1esMatchPhraseQueryBuilder.fromStandard((MatchPhraseQueryBuilder) query, client);
		}

		// multi_match → l1es_multi_match
		if (query instanceof MultiMatchQueryBuilder) {
			return L1esMultiMatchQueryBuilder.fromStandard((MultiMatchQueryBuilder) query, client);
		}

		// bool — recurse into all clause lists
		if (query instanceof BoolQueryBuilder) {
			return rewriteBool((BoolQueryBuilder) query, client);
		}

		// constant_score — recurse into inner query
		if (query instanceof ConstantScoreQueryBuilder) {
			return rewriteConstantScore((ConstantScoreQueryBuilder) query, client);
		}

		// boosting — recurse into positive and negative
		if (query instanceof BoostingQueryBuilder) {
			return rewriteBoosting((BoostingQueryBuilder) query, client);
		}

		// dis_max — recurse into disjuncts
		if (query instanceof DisMaxQueryBuilder) {
			return rewriteDisMax((DisMaxQueryBuilder) query, client);
		}

		// All other query types (term, range, exists, etc.) — pass through unchanged.
		return query;
	}

	private static QueryBuilder rewriteBool(BoolQueryBuilder bool, Client client) {
		List<QueryBuilder> must = rewriteList(bool.must(), client);
		List<QueryBuilder> should = rewriteList(bool.should(), client);
		List<QueryBuilder> mustNot = rewriteList(bool.mustNot(), client);
		List<QueryBuilder> filter = rewriteList(bool.filter(), client);

		// If no clauses changed, return original to avoid unnecessary object creation.
		if (must == null && should == null && mustNot == null && filter == null) {
			return bool;
		}

		BoolQueryBuilder rewritten = QueryBuilders.boolQuery();

		for (QueryBuilder q : (must != null ? must : bool.must())) {
			rewritten.must(q);
		}
		for (QueryBuilder q : (should != null ? should : bool.should())) {
			rewritten.should(q);
		}
		for (QueryBuilder q : (mustNot != null ? mustNot : bool.mustNot())) {
			rewritten.mustNot(q);
		}
		for (QueryBuilder q : (filter != null ? filter : bool.filter())) {
			rewritten.filter(q);
		}

		if (bool.minimumShouldMatch() != null) {
			rewritten.minimumShouldMatch(bool.minimumShouldMatch());
		}
		rewritten.adjustPureNegative(bool.adjustPureNegative());
		rewritten.queryName(bool.queryName());
		rewritten.boost(bool.boost());

		return rewritten;
	}

	private static QueryBuilder rewriteConstantScore(ConstantScoreQueryBuilder csq, Client client) {
		QueryBuilder inner = doRewrite(csq.innerQuery(), client);

		if (inner == csq.innerQuery()) {
			return csq;
		}

		ConstantScoreQueryBuilder rewritten = new ConstantScoreQueryBuilder(inner);
		rewritten.boost(csq.boost());
		rewritten.queryName(csq.queryName());
		return rewritten;
	}

	private static QueryBuilder rewriteBoosting(BoostingQueryBuilder bq, Client client) {
		QueryBuilder positive = doRewrite(bq.positiveQuery(), client);
		QueryBuilder negative = doRewrite(bq.negativeQuery(), client);

		if (positive == bq.positiveQuery() && negative == bq.negativeQuery()) {
			return bq;
		}

		BoostingQueryBuilder rewritten = new BoostingQueryBuilder(positive, negative);
		rewritten.negativeBoost(bq.negativeBoost());
		rewritten.boost(bq.boost());
		rewritten.queryName(bq.queryName());
		return rewritten;
	}

	private static QueryBuilder rewriteDisMax(DisMaxQueryBuilder dmq, Client client) {
		List<QueryBuilder> rewrittenInner = rewriteList(dmq.innerQueries(), client);

		if (rewrittenInner == null) {
			return dmq;
		}

		DisMaxQueryBuilder rewritten = new DisMaxQueryBuilder();
		for (QueryBuilder q : rewrittenInner) {
			rewritten.add(q);
		}
		rewritten.tieBreaker(dmq.tieBreaker());
		rewritten.boost(dmq.boost());
		rewritten.queryName(dmq.queryName());
		return rewritten;
	}

	/**
	 * Rewrites a list of query builders. Returns {@code null} if no changes
	 * were made (to allow callers to detect unchanged lists cheaply).
	 */
	private static List<QueryBuilder> rewriteList(List<QueryBuilder> queries, Client client) {
		if (queries == null || queries.isEmpty()) {
			return null;
		}

		List<QueryBuilder> result = null;

		for (int i = 0; i < queries.size(); i++) {
			QueryBuilder original = queries.get(i);
			QueryBuilder rewritten = doRewrite(original, client);

			if (rewritten != original && result == null) {
				// First change — copy all preceding elements.
				result = new ArrayList<>(queries.size());
				for (int j = 0; j < i; j++) {
					result.add(queries.get(j));
				}
			}

			if (result != null) {
				result.add(rewritten);
			}
		}

		return result;
	}
}
