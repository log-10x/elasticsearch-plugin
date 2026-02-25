package com.log10x.l1es.query;

import org.apache.lucene.search.Query;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.common.lucene.search.Queries;

import com.log10x.l1es.query.wrap.L1esQueryWrapper;

/**
 * Utility class for common {@link Query} related actions.
 */
public class QueryUtil {
	private static final String L1ES_QUERY_PREFIX = "l1es_";

	/**
	 * Prepends {@link #L1ES_QUERY_PREFIX} to a given {@code originalQueryName} to
	 * create the L1es name for that query.
	 * 
	 * @param originalQueryName Name of original query.
	 * @return Name of matching L1es query.
	 */
	public static String l1esQueryName(String originalQueryName) {
		return L1ES_QUERY_PREFIX + originalQueryName;
	}

	/**
	 * Potentially apply minimum should match value if we have a query that it can
	 * be applied to, otherwise return the original query.
	 * 
	 * This is essentially a wrapper for
	 * {@link Queries#maybeApplyMinimumShouldMatch} which handles "unwrapping" of
	 * {@link L1esQueryWrapper} queries and handling the internal queries instead.
	 * 
	 * TODO - make this more accurate, as the internal
	 * {@link L1esQueryWrapper#getInternal} might be more complicated in case of a
	 * lot of terms, or more complex cases.
	 * 
	 * @param query              the input {@link Query} to maybe apply minimum
	 *                           should match to.
	 * @param minimumShouldMatch the minimum should match value.
	 * 
	 * @return the resulting {@link Query}
	 */
	public static Query maybeApplyMinimumShouldMatch(Query query, @Nullable String minimumShouldMatch) {
		if (!(query instanceof L1esQueryWrapper)) {
			return Queries.maybeApplyMinimumShouldMatch(query, minimumShouldMatch);
		}

		L1esQueryWrapper wrapper = (L1esQueryWrapper) query;

		Query internal = wrapper.getInternal();
		Query maybe = Queries.maybeApplyMinimumShouldMatch(internal, minimumShouldMatch);

		if (internal == maybe) {
			return query;
		}

		return wrapper.withNewInternal(maybe);
	}
}
