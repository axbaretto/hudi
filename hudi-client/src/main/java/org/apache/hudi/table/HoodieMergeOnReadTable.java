/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table;

import org.apache.hudi.avro.model.HoodieCompactionPlan;
import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.common.HoodieRollbackStat;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.FileSlice;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.model.HoodieWriteStat;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.view.SyncableFileSystemView;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.ValidationUtils;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieCompactionException;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.table.action.commit.HoodieWriteMetadata;
import org.apache.hudi.table.action.deltacommit.BulkInsertDeltaCommitActionExecutor;
import org.apache.hudi.table.action.deltacommit.BulkInsertPreppedDeltaCommitActionExecutor;
import org.apache.hudi.table.action.deltacommit.DeleteDeltaCommitActionExecutor;
import org.apache.hudi.table.action.deltacommit.InsertDeltaCommitActionExecutor;
import org.apache.hudi.table.action.deltacommit.InsertPreppedDeltaCommitActionExecutor;
import org.apache.hudi.table.action.deltacommit.UpsertDeltaCommitActionExecutor;
import org.apache.hudi.table.action.deltacommit.UpsertPreppedDeltaCommitActionExecutor;
import org.apache.hudi.table.compact.HoodieMergeOnReadTableCompactor;
import org.apache.hudi.table.rollback.RollbackHelper;
import org.apache.hudi.table.rollback.RollbackRequest;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Implementation of a more real-time Hoodie Table the provides tradeoffs on read and write cost/amplification.
 *
 * <p>
 * INSERTS - Same as HoodieCopyOnWriteTable - Produce new files, block aligned to desired size (or) Merge with the
 * smallest existing file, to expand it
 * </p>
 * <p>
 * UPDATES - Appends the changes to a rolling log file maintained per file Id. Compaction merges the log file into the
 * base file.
 * </p>
 * <p>
 * WARNING - MOR table type does not support nested rollbacks, every rollback must be followed by an attempted commit
 * action
 * </p>
 */
public class HoodieMergeOnReadTable<T extends HoodieRecordPayload> extends HoodieCopyOnWriteTable<T> {

  private static final Logger LOG = LogManager.getLogger(HoodieMergeOnReadTable.class);

  HoodieMergeOnReadTable(HoodieWriteConfig config, JavaSparkContext jsc, HoodieTableMetaClient metaClient) {
    super(config, jsc, metaClient);
  }

  @Override
  public HoodieWriteMetadata upsert(JavaSparkContext jsc, String instantTime, JavaRDD<HoodieRecord<T>> records) {
    return new UpsertDeltaCommitActionExecutor<>(jsc, config, this, instantTime, records).execute();
  }

  @Override
  public HoodieWriteMetadata insert(JavaSparkContext jsc, String instantTime, JavaRDD<HoodieRecord<T>> records) {
    return new InsertDeltaCommitActionExecutor<>(jsc, config, this, instantTime, records).execute();
  }

  @Override
  public HoodieWriteMetadata bulkInsert(JavaSparkContext jsc, String instantTime, JavaRDD<HoodieRecord<T>> records,
      Option<UserDefinedBulkInsertPartitioner> bulkInsertPartitioner) {
    return new BulkInsertDeltaCommitActionExecutor<>(jsc, config,
        this, instantTime, records, bulkInsertPartitioner).execute();
  }

  @Override
  public HoodieWriteMetadata delete(JavaSparkContext jsc, String instantTime, JavaRDD<HoodieKey> keys) {
    return new DeleteDeltaCommitActionExecutor<>(jsc, config, this, instantTime, keys).execute();
  }

  @Override
  public HoodieWriteMetadata upsertPrepped(JavaSparkContext jsc, String instantTime,
      JavaRDD<HoodieRecord<T>> preppedRecords) {
    return new UpsertPreppedDeltaCommitActionExecutor<>(jsc, config, this, instantTime, preppedRecords).execute();
  }

  @Override
  public HoodieWriteMetadata insertPrepped(JavaSparkContext jsc, String instantTime,
      JavaRDD<HoodieRecord<T>> preppedRecords) {
    return new InsertPreppedDeltaCommitActionExecutor<>(jsc, config, this, instantTime, preppedRecords).execute();
  }

  @Override
  public HoodieWriteMetadata bulkInsertPrepped(JavaSparkContext jsc, String instantTime,
      JavaRDD<HoodieRecord<T>> preppedRecords,  Option<UserDefinedBulkInsertPartitioner> bulkInsertPartitioner) {
    return new BulkInsertPreppedDeltaCommitActionExecutor<>(jsc, config,
        this, instantTime, preppedRecords, bulkInsertPartitioner).execute();
  }

  @Override
  public HoodieCompactionPlan scheduleCompaction(JavaSparkContext jsc, String instantTime) {
    LOG.info("Checking if compaction needs to be run on " + config.getBasePath());
    Option<HoodieInstant> lastCompaction =
        getActiveTimeline().getCommitTimeline().filterCompletedInstants().lastInstant();
    String deltaCommitsSinceTs = "0";
    if (lastCompaction.isPresent()) {
      deltaCommitsSinceTs = lastCompaction.get().getTimestamp();
    }

    int deltaCommitsSinceLastCompaction = getActiveTimeline().getDeltaCommitTimeline()
        .findInstantsAfter(deltaCommitsSinceTs, Integer.MAX_VALUE).countInstants();
    if (config.getInlineCompactDeltaCommitMax() > deltaCommitsSinceLastCompaction) {
      LOG.info("Not running compaction as only " + deltaCommitsSinceLastCompaction
          + " delta commits was found since last compaction " + deltaCommitsSinceTs + ". Waiting for "
          + config.getInlineCompactDeltaCommitMax());
      return new HoodieCompactionPlan();
    }

    LOG.info("Compacting merge on read table " + config.getBasePath());
    HoodieMergeOnReadTableCompactor compactor = new HoodieMergeOnReadTableCompactor();
    try {
      return compactor.generateCompactionPlan(jsc, this, config, instantTime,
          ((SyncableFileSystemView) getSliceView()).getPendingCompactionOperations()
              .map(instantTimeCompactionopPair -> instantTimeCompactionopPair.getValue().getFileGroupId())
              .collect(Collectors.toSet()));

    } catch (IOException e) {
      throw new HoodieCompactionException("Could not schedule compaction " + config.getBasePath(), e);
    }
  }

  @Override
  public JavaRDD<WriteStatus> compact(JavaSparkContext jsc, String compactionInstantTime,
      HoodieCompactionPlan compactionPlan) {
    HoodieMergeOnReadTableCompactor compactor = new HoodieMergeOnReadTableCompactor();
    try {
      return compactor.compact(jsc, compactionPlan, this, config, compactionInstantTime);
    } catch (IOException e) {
      throw new HoodieCompactionException("Could not compact " + config.getBasePath(), e);
    }
  }

  @Override
  public List<HoodieRollbackStat> rollback(JavaSparkContext jsc, HoodieInstant instant,
      boolean deleteInstants) throws IOException {
    long startTime = System.currentTimeMillis();

    String commit = instant.getTimestamp();
    LOG.error("Rolling back instant " + instant);

    // Atomically un-publish all non-inflight commits
    if (instant.isCompleted()) {
      LOG.error("Un-publishing instant " + instant + ", deleteInstants=" + deleteInstants);
      instant = this.getActiveTimeline().revertToInflight(instant);
    }

    List<HoodieRollbackStat> allRollbackStats = new ArrayList<>();

    // At the moment, MOR table type does not support bulk nested rollbacks. Nested rollbacks is an experimental
    // feature that is expensive. To perform nested rollbacks, initiate multiple requests of client.rollback
    // (commitToRollback).
    // NOTE {@link HoodieCompactionConfig#withCompactionLazyBlockReadEnabled} needs to be set to TRUE. This is
    // required to avoid OOM when merging multiple LogBlocks performed during nested rollbacks.
    // Atomically un-publish all non-inflight commits
    // Atomically un-publish all non-inflight commits
    // For Requested State (like failure during index lookup), there is nothing to do rollback other than
    // deleting the timeline file
    if (!instant.isRequested()) {
      LOG.info("Unpublished " + commit);
      List<RollbackRequest> rollbackRequests = generateRollbackRequests(jsc, instant);
      // TODO: We need to persist this as rollback workload and use it in case of partial failures
      allRollbackStats = new RollbackHelper(metaClient, config).performRollback(jsc, instant, rollbackRequests);
    }

    // Delete Inflight instants if enabled
    deleteInflightAndRequestedInstant(deleteInstants, this.getActiveTimeline(), instant);

    LOG.info("Time(in ms) taken to finish rollback " + (System.currentTimeMillis() - startTime));

    return allRollbackStats;
  }

  /**
   * Generate all rollback requests that we need to perform for rolling back this action without actually performing
   * rolling back.
   * 
   * @param jsc JavaSparkContext
   * @param instantToRollback Instant to Rollback
   * @return list of rollback requests
   * @throws IOException
   */
  private List<RollbackRequest> generateRollbackRequests(JavaSparkContext jsc, HoodieInstant instantToRollback)
      throws IOException {
    String commit = instantToRollback.getTimestamp();
    List<String> partitions = FSUtils.getAllPartitionPaths(this.metaClient.getFs(), this.getMetaClient().getBasePath(),
        config.shouldAssumeDatePartitioning());
    int sparkPartitions = Math.max(Math.min(partitions.size(), config.getRollbackParallelism()), 1);
    return jsc.parallelize(partitions, Math.min(partitions.size(), sparkPartitions)).flatMap(partitionPath -> {
      HoodieActiveTimeline activeTimeline = this.getActiveTimeline().reload();
      List<RollbackRequest> partitionRollbackRequests = new ArrayList<>();
      switch (instantToRollback.getAction()) {
        case HoodieTimeline.COMMIT_ACTION:
          LOG.info(
              "Rolling back commit action. There are higher delta commits. So only rolling back this instant");
          partitionRollbackRequests.add(
              RollbackRequest.createRollbackRequestWithDeleteDataAndLogFilesAction(partitionPath, instantToRollback));
          break;
        case HoodieTimeline.COMPACTION_ACTION:
          // If there is no delta commit present after the current commit (if compaction), no action, else we
          // need to make sure that a compaction commit rollback also deletes any log files written as part of the
          // succeeding deltacommit.
          boolean higherDeltaCommits =
              !activeTimeline.getDeltaCommitTimeline().filterCompletedInstants().findInstantsAfter(commit, 1).empty();
          if (higherDeltaCommits) {
            // Rollback of a compaction action with no higher deltacommit means that the compaction is scheduled
            // and has not yet finished. In this scenario we should delete only the newly created parquet files
            // and not corresponding base commit log files created with this as baseCommit since updates would
            // have been written to the log files.
            LOG.info("Rolling back compaction. There are higher delta commits. So only deleting data files");
            partitionRollbackRequests.add(
                RollbackRequest.createRollbackRequestWithDeleteDataFilesOnlyAction(partitionPath, instantToRollback));
          } else {
            // No deltacommits present after this compaction commit (inflight or requested). In this case, we
            // can also delete any log files that were created with this compaction commit as base
            // commit.
            LOG.info("Rolling back compaction plan. There are NO higher delta commits. So deleting both data and"
                + " log files");
            partitionRollbackRequests.add(
                RollbackRequest.createRollbackRequestWithDeleteDataAndLogFilesAction(partitionPath, instantToRollback));
          }
          break;
        case HoodieTimeline.DELTA_COMMIT_ACTION:
          // --------------------------------------------------------------------------------------------------
          // (A) The following cases are possible if index.canIndexLogFiles and/or index.isGlobal
          // --------------------------------------------------------------------------------------------------
          // (A.1) Failed first commit - Inserts were written to log files and HoodieWriteStat has no entries. In
          // this scenario we would want to delete these log files.
          // (A.2) Failed recurring commit - Inserts/Updates written to log files. In this scenario,
          // HoodieWriteStat will have the baseCommitTime for the first log file written, add rollback blocks.
          // (A.3) Rollback triggered for first commit - Inserts were written to the log files but the commit is
          // being reverted. In this scenario, HoodieWriteStat will be `null` for the attribute prevCommitTime and
          // and hence will end up deleting these log files. This is done so there are no orphan log files
          // lying around.
          // (A.4) Rollback triggered for recurring commits - Inserts/Updates are being rolled back, the actions
          // taken in this scenario is a combination of (A.2) and (A.3)
          // ---------------------------------------------------------------------------------------------------
          // (B) The following cases are possible if !index.canIndexLogFiles and/or !index.isGlobal
          // ---------------------------------------------------------------------------------------------------
          // (B.1) Failed first commit - Inserts were written to parquet files and HoodieWriteStat has no entries.
          // In this scenario, we delete all the parquet files written for the failed commit.
          // (B.2) Failed recurring commits - Inserts were written to parquet files and updates to log files. In
          // this scenario, perform (A.1) and for updates written to log files, write rollback blocks.
          // (B.3) Rollback triggered for first commit - Same as (B.1)
          // (B.4) Rollback triggered for recurring commits - Same as (B.2) plus we need to delete the log files
          // as well if the base parquet file gets deleted.
          try {
            HoodieCommitMetadata commitMetadata = HoodieCommitMetadata.fromBytes(
                metaClient.getCommitTimeline()
                    .getInstantDetails(
                        new HoodieInstant(true, instantToRollback.getAction(), instantToRollback.getTimestamp()))
                    .get(),
                HoodieCommitMetadata.class);

            // In case all data was inserts and the commit failed, delete the file belonging to that commit
            // We do not know fileIds for inserts (first inserts are either log files or parquet files),
            // delete all files for the corresponding failed commit, if present (same as COW)
            partitionRollbackRequests.add(
                RollbackRequest.createRollbackRequestWithDeleteDataAndLogFilesAction(partitionPath, instantToRollback));

            // append rollback blocks for updates
            if (commitMetadata.getPartitionToWriteStats().containsKey(partitionPath)) {
              partitionRollbackRequests
                  .addAll(generateAppendRollbackBlocksAction(partitionPath, instantToRollback, commitMetadata));
            }
            break;
          } catch (IOException io) {
            throw new UncheckedIOException("Failed to collect rollback actions for commit " + commit, io);
          }
        default:
          break;
      }
      return partitionRollbackRequests.iterator();
    }).filter(Objects::nonNull).collect();
  }

  @Override
  public void finalizeWrite(JavaSparkContext jsc, String instantTs, List<HoodieWriteStat> stats)
      throws HoodieIOException {
    // delegate to base class for MOR tables
    super.finalizeWrite(jsc, instantTs, stats);
  }

  private List<RollbackRequest> generateAppendRollbackBlocksAction(String partitionPath, HoodieInstant rollbackInstant,
      HoodieCommitMetadata commitMetadata) {
    ValidationUtils.checkArgument(rollbackInstant.getAction().equals(HoodieTimeline.DELTA_COMMIT_ACTION));

    // wStat.getPrevCommit() might not give the right commit time in the following
    // scenario : If a compaction was scheduled, the new commitTime associated with the requested compaction will be
    // used to write the new log files. In this case, the commit time for the log file is the compaction requested time.
    // But the index (global) might store the baseCommit of the parquet and not the requested, hence get the
    // baseCommit always by listing the file slice
    Map<String, String> fileIdToBaseCommitTimeForLogMap = this.getSliceView().getLatestFileSlices(partitionPath)
        .collect(Collectors.toMap(FileSlice::getFileId, FileSlice::getBaseInstantTime));
    return commitMetadata.getPartitionToWriteStats().get(partitionPath).stream().filter(wStat -> {

      // Filter out stats without prevCommit since they are all inserts
      boolean validForRollback = (wStat != null) && (!wStat.getPrevCommit().equals(HoodieWriteStat.NULL_COMMIT))
          && (wStat.getPrevCommit() != null) && fileIdToBaseCommitTimeForLogMap.containsKey(wStat.getFileId());

      if (validForRollback) {
        // For sanity, log instant time can never be less than base-commit on which we are rolling back
        ValidationUtils
            .checkArgument(HoodieTimeline.compareTimestamps(fileIdToBaseCommitTimeForLogMap.get(wStat.getFileId()),
              rollbackInstant.getTimestamp(), HoodieTimeline.LESSER_OR_EQUAL));
      }

      return validForRollback && HoodieTimeline.compareTimestamps(fileIdToBaseCommitTimeForLogMap.get(
          // Base Ts should be strictly less. If equal (for inserts-to-logs), the caller employs another option
          // to delete and we should not step on it
          wStat.getFileId()), rollbackInstant.getTimestamp(), HoodieTimeline.LESSER);
    }).map(wStat -> {
      String baseCommitTime = fileIdToBaseCommitTimeForLogMap.get(wStat.getFileId());
      return RollbackRequest.createRollbackRequestWithAppendRollbackBlockAction(partitionPath, wStat.getFileId(),
          baseCommitTime, rollbackInstant);
    }).collect(Collectors.toList());
  }
}
