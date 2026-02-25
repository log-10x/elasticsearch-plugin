package com.log10x.l1es.job.es.document;

import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.client.internal.Client;

import com.log10x.l1es.job.es.ElasticJob;

/**
 * Job for deleting an item from an existing index in Elasticsearch.
 */
public class DeleteJob extends ElasticJob<DeleteRequest, DeleteResponse> {
	private final String indexName;

	private DeleteJob(DeleteRequestBuilder internalRequest, String indexName) {
		super(internalRequest);
		this.indexName = indexName;
	}

	@Override
	protected String operationName() {
		return "delete from " + indexName;
	}

	public static DeleteJob create(Client client, String indexName, String id) {
		DeleteRequestBuilder internalRequest = client.prepareDelete(indexName, id);
		return new DeleteJob(internalRequest, indexName);
	}
}
