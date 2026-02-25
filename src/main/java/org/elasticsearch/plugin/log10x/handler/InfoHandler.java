package org.elasticsearch.plugin.log10x.handler;

import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.plugin.log10x.L1esPlugin;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequest.Method;
import org.elasticsearch.rest.RestStatus;

/**
 * A rest handler for getting basic plugin info.
 */
public class InfoHandler extends BaseRestHandler
{
	@Override
	public List<Route> routes()
	{
		return Collections.singletonList(new Route(Method.GET, "_l1es"));
	}
	
	@Override
	public String getName()
	{
		return "l1es_plugin_info";
	}
	
	@Override
	protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client)
	{
		Properties props = L1esPlugin.properties();
		boolean pretty = restRequest.paramAsBoolean("pretty", false);
		
		return channel -> {
			XContentBuilder content = XContentFactory.jsonBuilder();
			
			if (pretty)
			{
				content.prettyPrint();
			}
			
			content.startObject();
			content.field("name", props.getProperty("l1es.name"));
			content.field("description", props.getProperty("l1es.desc"));
			
			content.startObject("version");
			content.field("l1es", props.getProperty("l1es.version"));
			content.field("l1es-snapshot", props.getProperty("l1es.snapshot"));
			content.field("elasticsearch", props.getProperty("es.version"));
			content.endObject();
			
			content.endObject();
			
			channel.sendResponse(new RestResponse(RestStatus.OK, content));
		};
	}
}
