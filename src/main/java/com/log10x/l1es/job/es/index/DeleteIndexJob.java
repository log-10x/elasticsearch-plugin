package com.log10x.l1es.job.es.index;

import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequestBuilder;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.internal.Client;

import com.log10x.l1es.job.es.ElasticJob;

/**
 * Job for deleting an index in Elasticsearch.
 */
public class DeleteIndexJob extends ElasticJob<DeleteIndexRequest, AcknowledgedResponse> {
	private final String indexName;

	private DeleteIndexJob(DeleteIndexRequestBuilder internalRequest, String indexName) {
		super(internalRequest);
		this.indexName = indexName;
	}

	@Override
	protected String operationName() {
		return "delete index " + indexName;
	}

	/**
	 * Factory method for creating a new {@link DeleteIndexJob}
	 *
	 * @param client    The {@link Client} which will handle the creation.
	 * @param indexName The name of the index to be deleted.
	 *
	 * @return a new {@link DeleteIndexJob}
	 */
	public static DeleteIndexJob create(Client client, String indexName) {

		DeleteIndexRequestBuilder internalRequest = client.admin().indices().prepareDelete(indexName);

		return new DeleteIndexJob(internalRequest, indexName);
	}
}
