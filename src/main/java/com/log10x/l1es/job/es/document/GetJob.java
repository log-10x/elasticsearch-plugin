package com.log10x.l1es.job.es.document;

import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetRequestBuilder;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.internal.Client;

import com.log10x.l1es.job.es.ElasticJob;

/**
 * Job for getting an item from an existing index in Elasticsearch.
 */
public class GetJob extends ElasticJob<GetRequest, GetResponse> {
	private final String indexName;

	private GetJob(GetRequestBuilder internalRequest, String indexName) {
		super(internalRequest);
		this.indexName = indexName;
	}

	@Override
	protected String operationName() {
		return "get from " + indexName;
	}

	public static GetJob create(Client client, String indexName, String id) {
		GetRequestBuilder internalRequest = client.prepareGet(indexName, id);
		return new GetJob(internalRequest, indexName);
	}
}
