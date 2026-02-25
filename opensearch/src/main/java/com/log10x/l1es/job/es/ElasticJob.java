package com.log10x.l1es.job.es;

import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.ActionRequestBuilder;
import org.opensearch.action.ActionRequest;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.action.ActionResponse;
import org.opensearch.common.Nullable;

import com.log10x.l1es.util.ESUtil;

/**
 * A wrapper class for execution of OpenSearch action requests. Allows
 * execution in an async or sync manner. (OpenSearch variant)
 *
 * Uses raw ActionRequestBuilder since OpenSearch removed the RequestBuilder
 * interface. The raw type allows subclasses like IndexJob to pass concrete
 * builders (e.g., IndexRequestBuilder) without generic type mismatch.
 */
public abstract class ElasticJob<RQ extends ActionRequest, RS extends ActionResponse> {
	private static final Logger logger = LogManager.getLogger(ElasticJob.class);

	/**
	 * The internal {@link ActionRequestBuilder} to work with.
	 */
	@SuppressWarnings("rawtypes")
	protected final ActionRequestBuilder internalRequest;

	/**
	 * @param internalRequest The internal {@link ActionRequestBuilder} to work
	 *                        with.
	 */
	@SuppressWarnings("rawtypes")
	protected ElasticJob(ActionRequestBuilder internalRequest) {
		this.internalRequest = internalRequest;
	}

	/**
	 * @return a pretty name of this operation, used for logging.
	 */
	protected abstract String operationName();

	/**
	 * Executes the internal job returns the {@link ActionResponse}
	 *
	 * Attempting to run from a thread that can't run sync OpenSearch actions
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
	@SuppressWarnings("unchecked")
	public RS get(@Nullable Predicate<Exception> errorHandler) {
		if (!ESUtil.canPerformSyncActions()) {
			logger.warn("Can't sync {}.", operationName());

			return null;
		}

		try {
			return (RS) internalRequest.get();
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
	@SuppressWarnings("unchecked")
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
