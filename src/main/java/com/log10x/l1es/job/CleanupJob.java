package com.log10x.l1es.job;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.rest.RestStatus;

import com.log10x.l1es.dml.DmlConsts;
import com.log10x.l1es.job.es.index.DeleteIndexJob;
import com.log10x.l1es.util.ESUtil;
import com.log10x.l1es.util.ResponseUtil;

/**
 * Job for cleaning up the resources created by {@link SetupJob}
 * 
 * Removes two indices:
 * 
 * 1. {@link DmlConsts#L1ES_DML_INDICES_INDEX_NAME}
 *
 * 2. {@link DmlConsts#DML_INDEX_NAME}
 * 
 * This job will attempt to delete the first index first, and will only continue
 * to the second one if it succeeds.
 * 
 * If an {@link Exception} was throw as part of an index deletion process, that
 * exception will be passed to the {@code endListener}.
 * 
 * If no {@link Exception} was thrown, but the
 * {@link AcknowledgedResponse#isAcknowledged} is {@code false}, we pass a
 * {@link ResponseUtil#serverError} to {@code endListener} which propagates to
 * the caller with a {@link RestStatus#INTERNAL_SERVER_ERROR}
 * 
 * Failing to delete an index because it doesn't exist doesn't constitute as a
 * failure from the perspective of this job.
 * 
 */
public class CleanupJob {
	private static final Logger logger = LogManager.getLogger(CleanupJob.class);

	private final Client client;
	private final ActionListener<Boolean> endListener;

	/**
	 * @param client      The {@link Client} which will handle the index deletion.
	 * @param endListener A {@link ActionListener} to handle success/failure of this
	 *                    job.
	 */
	private CleanupJob(Client client, ActionListener<Boolean> endListener) {
		this.client = client;
		this.endListener = endListener;
	}

	/**
	 * Executes this {@link CleanupJob}
	 */
	public void execute() {
		try {
			DeleteIndexJob job = DeleteIndexJob.create(client, DmlConsts.L1ES_DML_INDICES_INDEX_NAME);

			job.execute(new ActionListener<AcknowledgedResponse>() {
				@Override
				public void onResponse(AcknowledgedResponse response) {
					if (!response.isAcknowledged()) {
						logger.warn("Failed deleting DML indices index {}.", DmlConsts.L1ES_DML_INDICES_INDEX_NAME);

						endListener.onFailure(ResponseUtil.serverError(
								"Failed deleting DML indices index " + DmlConsts.L1ES_DML_INDICES_INDEX_NAME));

						return;
					}

					continueWithDmlIndex();
				}

				@Override
				public void onFailure(Exception e) {
					if (ESUtil.indexMissingException(e)) {
						// Index missing isn't an actual issue, we can proceed.
						//
						continueWithDmlIndex();
						return;
					}

					endListener.onFailure(e);
				}
			});
		} catch (Exception e) {
			endListener.onFailure(e);
		}
	}

	private void continueWithDmlIndex() {
		try {
			DeleteIndexJob job = DeleteIndexJob.create(client, DmlConsts.DML_INDEX_NAME);

			job.execute(new ActionListener<AcknowledgedResponse>() {
				@Override
				public void onResponse(AcknowledgedResponse response) {
					if (!response.isAcknowledged()) {
						logger.warn("Failed deleting DML index {}.", DmlConsts.DML_INDEX_NAME);

						endListener.onFailure(
								ResponseUtil.serverError("Failed deleting DML index " + DmlConsts.DML_INDEX_NAME));

						return;
					}

					endListener.onResponse(true);
				}

				@Override
				public void onFailure(Exception e) {
					if (ESUtil.indexMissingException(e)) {
						// Index missing isn't an actual issue, we can call this a success.
						//
						endListener.onResponse(true);
						return;
					}

					endListener.onFailure(e);
				}
			});
		} catch (Exception e) {
			endListener.onFailure(e);
		}
	}

	/**
	 * Creates and executes a new {@link CleanupJob}
	 * 
	 * @param client      the {@link Client} to use for operations against the ES
	 *                    cluster.
	 * @param endListener a listener to invoke once the job execution finishes.
	 */
	public static void execute(Client client, ActionListener<Boolean> endListener) {
		CleanupJob job = new CleanupJob(client, endListener);
		job.execute();
	}
}
