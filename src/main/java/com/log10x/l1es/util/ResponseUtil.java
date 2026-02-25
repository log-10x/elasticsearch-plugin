package com.log10x.l1es.util;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestStatus;

/**
 * Utility class for common response channel related actions.
 */
public class ResponseUtil {
	public static ElasticsearchStatusException badRequest(String message) {
		return new ElasticsearchStatusException(message, RestStatus.BAD_REQUEST);
	}

	public static ElasticsearchStatusException notFound(String message) {
		return new ElasticsearchStatusException(message, RestStatus.NOT_FOUND);
	}

	public static ElasticsearchStatusException serverError(String message) {
		return new ElasticsearchStatusException(message, RestStatus.INTERNAL_SERVER_ERROR);
	}

	public static ElasticsearchStatusException notImpl(String message) {
		return new ElasticsearchStatusException(message, RestStatus.NOT_IMPLEMENTED);
	}

	public static void sendError(Exception exception, RestChannel channel, Logger logger) {
		try {
			if (exception instanceof ElasticsearchException) {
				logger.error("Elasticsearch error.", exception);
				channel.sendResponse(
						new RestResponse(channel, ((ElasticsearchException) exception).status(), exception));
			} else {
				logger.error("Unexpected error.", exception);
				channel.sendResponse(new RestResponse(channel, RestStatus.INTERNAL_SERVER_ERROR, exception));
			}
		} catch (Exception e) {
			logger.error("Unexpected error while handling an error.", exception);
			String message = "{\"error\":{\"message\":\"" + JsonUtil.jsonQuoteString(e.getMessage())
					+ "\",\"status\":500}}";
			channel.sendResponse(new RestResponse(RestStatus.INTERNAL_SERVER_ERROR, message));
		}
	}
}
