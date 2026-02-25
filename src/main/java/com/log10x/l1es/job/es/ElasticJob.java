package com.log10x.l1es.job.es;

import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.RequestBuilder;
import org.elasticsearch.core.Nullable;

import com.log10x.l1es.util.ESUtil;

/**
 * A wrapper class for execution of Elasticsearch action requests. Allows
 * execution in an async or sync manner.
 */
public abstract class ElasticJob<RQ extends ActionRequest, RS extends ActionResponse> {
	private static final Logger logger = LogManager.getLogger(ElasticJob.class);

	/**
	 * The internal {@link RequestBuilder} to work with.
	 */
	protected final RequestBuilder<RQ, RS> internalRequest;

	/**
	 * @param internalRequest The internal {@link RequestBuilder} to work
	 *                        with.
	 */
	protected ElasticJob(RequestBuilder<RQ, RS> internalRequest) {
		this.internalRequest = internalRequest;
	}

	/**
	 * @return a pretty name of this operation, used for logging.
	 */
	protected abstract String operationName();

	/**
	 * Executes the internal job returns the {@link ActionResponse}
	 *
	 * Attempting to run from a thread that can't run sync Elasticsearch actions
	 * (validated by {@link ESUtil#canPerformSyncActions}) returns {@code null}
	 *
	 * @param errorHandler A handler for handling any exceptions thrown while
	 *                     attempting to run. If passed {@code null} or
	 *                     {@link Predicate#test} returns {@code false}, the default
	 *                     handling is to emit an error message to the log.
	 *
	 * @return The result {@link ActionResponse}, or {@code null} in case of an
	 *         error or trying to run this from a thread that can't run sync
	 *         operations.
	 */
	public RS get(@Nullable Predicate<Exception> errorHandler) {
		if (!ESUtil.canPerformSyncActions()) {
			logger.warn("Can't sync {}.", operationName());

			return null;
		}

		try {
			return internalRequest.get();
		} catch (Exception e) {
			if ((errorHandler == null) || (!errorHandler.test(e))) {
				logger.error("Error while performing {}.", operationName(), e);
			}

			return null;
		}
	}

	/**
	 * Executes the internal job and propagates the {@link ActionResponse} to the
	 * provided {@code listener}
	 *
	 * Wraps the provided {@code listener} with another {@link ActionListener} which
	 * adds a {code try} block around {@code listener.onResponse}
	 *
	 * @param listener Target listener for the response from execution.
	 */
	public void execute(ActionListener<RS> listener) {
		internalRequest.execute(new ActionListener<RS>() {
			@Override
			public void onResponse(RS response) {
				try {
					listener.onResponse(response);
				} catch (Exception e) {
					listener.onFailure(e);
				}
			}

			@Override
			public void onFailure(Exception e) {
				listener.onFailure(e);
			}
		});
	}
}
