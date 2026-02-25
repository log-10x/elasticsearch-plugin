package com.log10x.l1es.job;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.plugin.log10x.L1esConfig;
import org.elasticsearch.rest.RestStatus;

import com.log10x.l1es.dml.DmlConsts;
import com.log10x.l1es.dml.DmlIndex;
import com.log10x.l1es.job.es.index.CreateIndexJob;
import com.log10x.l1es.util.ResponseUtil;

/**
 * Job for setting up the needed resources for L1esPlugin to work properly.
 * 
 * Creates two indices:
 * 
 * 1. {@link DmlConsts#L1ES_DML_INDICES_INDEX_NAME} with
 * {@link DmlConsts#L1ES_DML_INDICES_INDEX_MAPPING} This index is responsible
 * for holding the data about which fields in which documents are encoded by
 * L1x, and to which field they should be decoded. See {@link DmlIndex} for more
 * info.
 *
 * 2. {@link DmlConsts#DML_INDEX_NAME} with {@link DmlConsts#DML_INDEX_MAPPING}
 * This index is responsible for holding all the data needed for actual
 * decoding, mapping each encoded pattern hash id to the decoded form.
 * 
 * This job will attempt to create the first index first, and will only continue
 * to the second one if it succeeds.
 * 
 * If an {@link Exception} was throw as part of an index creation process, that
 * exception will be passed to the {@code endListener}.
 * 
 * If no {@link Exception} was thrown, but the
 * {@link CreateIndexResponse#isAcknowledged} is {@code false}, we pass a
 * {@link ResponseUtil#serverError} to {@code endListener} which propagates to
 * the caller with a {@link RestStatus#INTERNAL_SERVER_ERROR}
 * 
 */
public class SetupJob {
	private static final Logger logger = LogManager.getLogger(SetupJob.class);

	private final Client client;
	private final ActionListener<Boolean> endListener;

	/**
	 * @param client      The {@link Client} which will handle the index creation.
	 * @param endListener A {@link ActionListener} to handle success/failure of this
	 *                    job.
	 */
	private SetupJob(Client client, ActionListener<Boolean> endListener) {
		this.client = client;
		this.endListener = endListener;
	}

	/**
	 * Executes this {@link SetupJob}
	 */
	public void execute() {
		try {
			CreateIndexJob job = CreateIndexJob.create(
					client,
					DmlConsts.L1ES_DML_INDICES_INDEX_NAME,
					DmlConsts.L1ES_DML_INDICES_INDEX_MAPPING,
					L1esConfig.get().dml.indicesIndexNumberOfShards,
					L1esConfig.get().dml.indicesIndexNumberOfReplicas);

			job.execute(new ActionListener<CreateIndexResponse>() {
				@Override
				public void onResponse(CreateIndexResponse response) {
					if (!response.isAcknowledged()) {
						logger.warn("Failed creating DML indices index {}.", DmlConsts.L1ES_DML_INDICES_INDEX_NAME);

						endListener.onFailure(ResponseUtil.serverError(
								"Failed creating DML indices index " + DmlConsts.L1ES_DML_INDICES_INDEX_NAME));

						return;
					}

					continueWithDmlIndex();
				}

				@Override
				public void onFailure(Exception e) {
					endListener.onFailure(e);
				}
			});
		} catch (Exception e) {
			endListener.onFailure(e);
		}
	}

	private void continueWithDmlIndex() {
		try {
			CreateIndexJob job = CreateIndexJob.create(
					client,
					DmlConsts.DML_INDEX_NAME,
					DmlConsts.DML_INDEX_MAPPING,
					L1esConfig.get().dml.dmlIndexNumberOfShards,
					L1esConfig.get().dml.dmlIndexNumberOfReplicas);

			job.execute(new ActionListener<CreateIndexResponse>() {
				@Override
				public void onResponse(CreateIndexResponse response) {
					if (!response.isAcknowledged()) {
						logger.warn("Failed creating DML index {}.", DmlConsts.DML_INDEX_NAME);

						endListener.onFailure(
								ResponseUtil.serverError("Failed creating DML index " + DmlConsts.DML_INDEX_NAME));

						return;
					}

					endListener.onResponse(true);
				}

				@Override
				public void onFailure(Exception e) {
					endListener.onFailure(e);
				}
			});
		} catch (Exception e) {
			endListener.onFailure(e);
		}
	}

	/**
	 * Creates and executes a new {@link SetupJob}
	 * 
	 * @param client      the {@link Client} to use for operations against the ES
	 *                    cluster.
	 * @param endListener a listener to invoke once the job execution finishes.
	 */
	public static void execute(Client client, ActionListener<Boolean> endListener) {
		SetupJob job = new SetupJob(client, endListener);
		job.execute();
	}
}
