package com.log10x.l1es.filter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.core.RefCounted;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.plugin.log10x.L1esConfig;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.tasks.Task;

import com.log10x.l1es.query.L1esQueryRewriter;

/**
 * An {@link ActionFilter} that transparently rewrites standard Elasticsearch
 * queries to their L1ES equivalents. This enables Kibana dashboards to work
 * without changes against L1ES-encoded data.
 *
 * Intercepts search requests and replaces {@code match}, {@code match_phrase},
 * and {@code multi_match} queries with L1ES variants that can search encoded
 * content.
 */
public class L1esQueryRewriteFilter implements ActionFilter {
	private static final Logger logger = LogManager.getLogger(L1esQueryRewriteFilter.class);

	private static final String SEARCH_ACTION = "indices:data/read/search";

	private volatile Client client;

	/**
	 * Sets the ES client used by the L1ES query builders.
	 */
	public void setClient(Client client) {
		this.client = client;
	}

	@Override
	public int order() {
		return Integer.MAX_VALUE;
	}

	@Override
	public <Request extends ActionRequest, Response extends ActionResponse> void apply(
			Task task, String action, Request request, ActionListener<Response> listener,
			ActionFilterChain<Request, Response> chain) {

		if (SEARCH_ACTION.equals(action) && client != null
				&& L1esConfig.get().flags.queryRewriteEnabled()
				&& request instanceof SearchRequest) {
			try {
				rewriteSearchRequest((SearchRequest) request);
			} catch (Exception e) {
				logger.error("Error rewriting search request, proceeding with original.", e);
			}
		}

		chain.proceed(task, action, request, listener);
	}

	private void rewriteSearchRequest(SearchRequest searchRequest) {
		SearchSourceBuilder source = searchRequest.source();

		if (source == null || source.query() == null) {
			return;
		}

		QueryBuilder original = source.query();
		QueryBuilder rewritten = L1esQueryRewriter.rewrite(original, client);

		if (rewritten != original) {
			source.query(rewritten);
			logger.debug("Rewrote search query for transparent L1ES support.");
		}
	}
}
