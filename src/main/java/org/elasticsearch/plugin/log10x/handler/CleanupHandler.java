package org.elasticsearch.plugin.log10x.handler;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequest.Method;

import com.log10x.l1es.job.CleanupJob;
import com.log10x.l1es.util.ResponseUtil;

import org.elasticsearch.rest.RestStatus;

/**
 * A rest handler for invoking a {@link CleanupJob} which will remove plugin
 * resources created by {@link SetupHandler} resources.
 */
public class CleanupHandler extends BaseRestHandler {
	private static final Logger logger = LogManager.getLogger(CleanupHandler.class);

	@Override
	public List<Route> routes() {
		return Collections.singletonList(new Route(Method.POST, "_l1es/cleanup"));
	}

	@Override
	public String getName() {
		return "l1es_cleanup";
	}

	@Override
	protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) {
		boolean pretty = restRequest.paramAsBoolean("pretty", false);

		return channel -> {
			Consumer<Exception> errorHandler = (e) -> ResponseUtil.sendError(e, channel, logger);

			try {
				CleanupJob.execute(client, new ActionListener<Boolean>() {
					@Override
					public void onResponse(Boolean response) {
						try {
							XContentBuilder content = XContentFactory.jsonBuilder();

							if (pretty) {
								content.prettyPrint();
							}

							content.startObject().field("acknowledged", response).endObject();

							channel.sendResponse(new RestResponse(RestStatus.OK, content));
						} catch (Exception e) {
							errorHandler.accept(e);
						}
					}

					@Override
					public void onFailure(Exception e) {
						errorHandler.accept(e);
					}
				});
			} catch (Exception e) {
				errorHandler.accept(e);
			}
		};
	}
}
