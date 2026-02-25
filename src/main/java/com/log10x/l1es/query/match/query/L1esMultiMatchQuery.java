package com.log10x.l1es.query.match.query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.index.query.ZeroTermsQueryOption;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.SearchExecutionContext;

import com.log10x.l1es.dml.DmlDB;
import com.log10x.l1es.query.QueryUtil;
import com.log10x.l1es.query.match.L1esMultiMatchQueryBuilder;

/**
 * Class for creating {@link Query}s when specifically querying fields which are
 * L1x encoded, in a "multi_match" query.
 */
public class L1esMultiMatchQuery extends L1esMatchQuery {
	private Float groupTieBreaker = null;

	public L1esMultiMatchQuery(SearchExecutionContext context, Client client, DmlDB dmlDB) {
		super(context, client, dmlDB);
	}

	public void setTieBreaker(float tieBreaker) {
		this.groupTieBreaker = tieBreaker;
	}

	public Query parse(MultiMatchQueryBuilder.Type type, Map<String, Float> fieldNames, Object value,
			String minimumShouldMatch) throws IOException {
		boolean hasMappedField = fieldNames.keySet().stream().anyMatch(k -> context.getFieldType(k) != null);

		if (hasMappedField == false) {
			return Queries.newUnmappedFieldsQuery(fieldNames.keySet());
		}

		final float tieBreaker = ((groupTieBreaker == null) ? type.tieBreaker() : groupTieBreaker);

		final List<Query> queries;

		switch (type) {
		case PHRASE:
		case PHRASE_PREFIX:
		case BEST_FIELDS:
		case MOST_FIELDS:
		case BOOL_PREFIX:
			queries = buildFieldQueries(type, fieldNames, value, minimumShouldMatch);
			break;

		case CROSS_FIELDS:
			throw new IOException("CROSS FIELDS NOT IMPLEMENTED");

		default:
			throw new IllegalStateException("No such type: " + type);
		}

		return combineGrouped(queries, tieBreaker);
	}

	private Query combineGrouped(List<Query> groupQuery, float tieBreaker) {
		if (groupQuery.isEmpty()) {
			return zeroTermsQuery == ZeroTermsQueryOption.ALL
					? new MatchAllDocsQuery()
					: new MatchNoDocsQuery("no terms in l1es multi_match query");
		}

		if (groupQuery.size() == 1) {
			return groupQuery.get(0);
		}

		return new DisjunctionMaxQuery(groupQuery, tieBreaker);
	}

	private List<Query> buildFieldQueries(MultiMatchQueryBuilder.Type type, Map<String, Float> fieldNames, Object value,
			String minimumShouldMatch) throws IOException {
		List<Query> queries = new ArrayList<>();

		for (String fieldName : fieldNames.keySet()) {
			if (context.getFieldType(fieldName) == null) {
				continue;
			}

			float boostValue = fieldNames.getOrDefault(fieldName, 1.0f);

			Query query = parse(type.matchQueryType(), fieldName, value);

			query = QueryUtil.maybeApplyMinimumShouldMatch(query, minimumShouldMatch);

			if ((query != null) && (boostValue != AbstractQueryBuilder.DEFAULT_BOOST)
					&& (!(query instanceof MatchNoDocsQuery))) {

				query = new BoostQuery(query, boostValue);
			}

			if (query != null) {
				queries.add(query);
			}
		}

		return queries;
	}
}
