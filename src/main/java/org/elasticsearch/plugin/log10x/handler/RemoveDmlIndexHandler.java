package org.elasticsearch.plugin.log10x.handler;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequest.Method;

import com.log10x.l1es.dml.DmlConsts;
import com.log10x.l1es.dml.DmlIndex;
import com.log10x.l1es.dml.DmlUtil;
import com.log10x.l1es.job.es.document.DeleteJob;
import com.log10x.l1es.util.ResponseUtil;

import org.elasticsearch.rest.RestStatus;

/**
 * A {@link RestHandler} responsible for removing a mapping for an L1x encoded
 * field in an Elasticsearch index.
 * 
 * Done by deleting a matching {@link DmlIndex} from the
 * {@link DmlConsts#L1ES_DML_INDICES_INDEX_NAME} index.
 * 
 */
public class RemoveDmlIndexHandler extends BaseRestHandler {
	private static final Logger logger = LogManager.getLogger(RemoveDmlIndexHandler.class);

	@Override
	public List<Route> routes() {
		return Collections.singletonList(new Route(Method.POST, "_l1es/remove-dml-index"));
	}

	@Override
	public String getName() {
		return "remove_add_dml_index";
	}

	@Override
	protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) throws IOException {
		boolean pretty = restRequest.paramAsBoolean("pretty", false);
		XContentParser parser = restRequest.contentOrSourceParamParser();

		return channel -> {
			Consumer<Exception> errorHandler = (e) -> ResponseUtil.sendError(e, channel, logger);

			try {
				DmlIndex dmlIndex = DmlIndex.parseXContent(parser, false, true);

				DeleteJob job = DeleteJob.create(client, DmlConsts.L1ES_DML_INDICES_INDEX_NAME,
						dmlIndex.affectedIndexName);

				job.execute(new ActionListener<DeleteResponse>() {
					@Override
					public void onResponse(DeleteResponse response) {
						DmlUtil.clearDmlIndexCache();

						try {
							XContentBuilder content = XContentFactory.jsonBuilder();

							if (pretty) {
								content.prettyPrint();
							}

							content.startObject().field("response", response).endObject();

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
