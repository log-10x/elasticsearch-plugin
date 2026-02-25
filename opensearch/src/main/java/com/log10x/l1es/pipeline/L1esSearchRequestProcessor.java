package com.log10x.l1es.pipeline;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.client.Client;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.plugin.log10x.L1esConfig;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.pipeline.AbstractProcessor;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchRequestProcessor;

import com.log10x.l1es.query.L1esQueryRewriter;

/**
 * An OpenSearch {@link SearchRequestProcessor} that transparently rewrites
 * standard queries to their L1ES equivalents. This enables Kibana dashboards
 * to work without changes against L1ES-encoded data.
 */
public class L1esSearchRequestProcessor extends AbstractProcessor implements SearchRequestProcessor {
	private static final Logger logger = LogManager.getLogger(L1esSearchRequestProcessor.class);

	public static final String TYPE = "l1es_query_rewrite";

	private final Client client;

	public L1esSearchRequestProcessor(String tag, String description, boolean ignoreFailure, Client client) {
		super(tag, description, ignoreFailure);
		this.client = client;
	}

	@Override
	public String getType() {
		return TYPE;
	}

	@Override
	public SearchRequest processRequest(SearchRequest request) throws Exception {
		if (client == null || !L1esConfig.get().flags.queryRewriteEnabled()) {
			return request;
		}

		SearchSourceBuilder source = request.source();

		if (source == null || source.query() == null) {
			return request;
		}

		QueryBuilder original = source.query();
		QueryBuilder rewritten = L1esQueryRewriter.rewrite(original, client);

		if (rewritten != original) {
			source.query(rewritten);
			logger.debug("Rewrote search query for transparent L1ES support.");
		}

		return request;
	}

	/**
	 * Factory for creating {@link L1esSearchRequestProcessor} instances.
	 */
	public static class Factory implements Processor.Factory<SearchRequestProcessor> {
		private final Client client;

		public Factory(Client client) {
			this.client = client;
		}

		@Override
		public SearchRequestProcessor create(
				java.util.Map<String, Processor.Factory<SearchRequestProcessor>> processorFactories,
				String tag, String description, boolean ignoreFailure,
				java.util.Map<String, Object> config,
				Processor.PipelineContext pipelineContext) throws Exception {
			return new L1esSearchRequestProcessor(tag, description, ignoreFailure, client);
		}
	}
}
