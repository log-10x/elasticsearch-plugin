package com.log10x.l1es.job.es.document;

import java.util.Collection;

import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.get.MultiGetRequestBuilder;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.client.internal.Client;

import com.log10x.l1es.job.es.ElasticJob;

/**
 * Job for getting multiple items from an existing index in Elasticsearch.
 */
public class MultiGetJob extends ElasticJob<MultiGetRequest, MultiGetResponse> {
	private final String indexName;

	private MultiGetJob(MultiGetRequestBuilder internalRequest, String indexName) {
		super(internalRequest);
		this.indexName = indexName;
	}

	@Override
	protected String operationName() {
		return "multi get from " + indexName;
	}

	public static MultiGetJob create(Client client, String indexName, Collection<String> ids) {
		MultiGetRequestBuilder internalRequest = client.prepareMultiGet();
		for (String id : ids) {
			internalRequest.add(indexName, id);
		}
		return new MultiGetJob(internalRequest, indexName);
	}
}
