package com.log10x.l1es.job.es.document;

import java.util.Map;

import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.client.internal.Client;

import com.log10x.l1es.job.es.ElasticJob;

/**
 * Job for indexing an item into an existing index in Elasticsearch.
 */
public class IndexJob extends ElasticJob<IndexRequest, DocWriteResponse> {
	private final String indexName;

	private IndexJob(IndexRequestBuilder internalRequest, String indexName) {
		super(internalRequest);
		this.indexName = indexName;
	}

	@Override
	protected String operationName() {
		return "index into " + indexName;
	}

	public static IndexJob create(Client client, String indexName, String id, Map<String, ?> source) {
		IndexRequestBuilder internalRequest = client.prepareIndex(indexName).setId(id).setSource(source);
		return new IndexJob(internalRequest, indexName);
	}
}
