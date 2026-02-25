package com.log10x.l1es.job.es;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.index.query.QueryBuilder;

/**
 * Job for running a search query on an Elasticsearch index.
 */
public class SearchJob extends ElasticJob<SearchRequest, SearchResponse> {
	private final String indexName;

	private SearchJob(SearchRequestBuilder internalRequest, String indexName) {
		super(internalRequest);

		this.indexName = indexName;
	}

	@Override
	protected String operationName() {
		return "search in " + indexName;
	}

	/**
	 * Factory method for creating a new {@link SearchJob}
	 *
	 * @param client    The {@link Client} which will handle the creation.
	 * @param indexName The name of the index to search on.
	 * @param query     The search query for this job.
	 * @param size      The number of search hits to return. A non-positive input is
	 *                  ignored.
	 *
	 * @return a new {@link SearchJob}
	 */
	public static SearchJob create(Client client, String indexName, QueryBuilder query, int size) {
		SearchRequestBuilder internalRequest = client.prepareSearch(indexName).setQuery(query);

		if (size > 0) {
			internalRequest.setSize(size);
		}

		return new SearchJob(internalRequest, indexName);
	}
}
