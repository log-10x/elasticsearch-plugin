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

import com.log10x.l1es.job.SetupJob;
import com.log10x.l1es.util.ResponseUtil;

import org.elasticsearch.rest.RestStatus;

/**
 * A rest handler for invoking a {@link SetupJob} which will setup needed plugin
 * resources.
 */
public class SetupHandler extends BaseRestHandler {
	private static final Logger logger = LogManager.getLogger(SetupHandler.class);

	@Override
	public List<Route> routes() {
		return Collections.singletonList(new Route(Method.POST, "_l1es/setup"));
	}

	@Override
	public String getName() {
		return "l1es_setup";
	}

	@Override
	protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) {
		boolean pretty = restRequest.paramAsBoolean("pretty", false);

		return channel -> {
			Consumer<Exception> errorHandler = (e) -> ResponseUtil.sendError(e, channel, logger);

			try {
				// In the future we may consider to allow direct setting of number
				// of shards/replicas and even index name from here.
				//
				// In the meantime, shards/replicas can be configurable in the
				// plugin config yml, and index names are hard coded.
				//
				SetupJob.execute(client, new ActionListener<Boolean>() {
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
