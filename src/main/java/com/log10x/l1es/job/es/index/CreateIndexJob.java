package com.log10x.l1es.job.es.index;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.settings.Settings;

import com.log10x.l1es.job.es.ElasticJob;

/**
 * Job for creating a new index in Elasticsearch.
 */
public class CreateIndexJob extends ElasticJob<CreateIndexRequest, CreateIndexResponse> {
	private final String indexName;

	private CreateIndexJob(CreateIndexRequestBuilder internalRequest, String indexName) {
		super(internalRequest);
		this.indexName = indexName;
	}

	@Override
	protected String operationName() {
		return "create index " + indexName;
	}

	public static CreateIndexJob create(Client client, String indexName, String indexMapping, int numberOfShards,
			int numberOfReplicas) {

		CreateIndexRequestBuilder internalRequest = client.admin().indices().prepareCreate(indexName)
				.setMapping(indexMapping)
				.setSettings(Settings.builder().put("index.number_of_shards", numberOfShards)
						.put("index.number_of_replicas", numberOfReplicas));

		return new CreateIndexJob(internalRequest, indexName);
	}
}
