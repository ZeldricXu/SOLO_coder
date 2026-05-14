package com.datasync.service.engine.impl;

import com.datasync.common.Constants;
import com.datasync.dto.SyncExecuteRequest;
import com.datasync.model.*;
import com.datasync.service.conflict.ConflictDetector;
import com.datasync.service.conflict.ConflictHandler;
import com.datasync.service.config.ConfigManager;
import com.datasync.service.datasource.DataSourceAdapter;
import com.datasync.service.datasource.DataSourceAdapterFactory;
import com.datasync.service.engine.SyncEngine;
import com.datasync.service.log.SyncLogger;
import com.datasync.service.monitor.StatusMonitor;
import com.datasync.service.read.AsyncDataReader;
import com.datasync.service.retry.RetryScheduler;
import com.datasync.service.version.VersionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SyncEngineImpl implements SyncEngine {

    private static final Logger logger = LoggerFactory.getLogger(SyncEngineImpl.class);

    private final Set<String> runningTasks = ConcurrentHashMap.newKeySet();
    private final Set<String> pausedTasks = ConcurrentHashMap.newKeySet();
    private final Map<String, SyncRecord> activeSyncRecords = new ConcurrentHashMap<>();

    @Autowired
    private ConfigManager configManager;

    @Autowired
    private DataSourceAdapterFactory adapterFactory;

    @Autowired
    private VersionManager versionManager;

    @Autowired
    private ConflictDetector conflictDetector;

    @Autowired
    private ConflictHandler conflictHandler;

    @Autowired
    private StatusMonitor statusMonitor;

    @Autowired
    private SyncLogger syncLogger;

    @Autowired(required = false)
    private AsyncDataReader asyncDataReader;

    @Autowired(required = false)
    private RetryScheduler retryScheduler;

    @Override
    public SyncRecord executeSync(SyncExecuteRequest request) {
        String taskId = request.getTaskId();
        String syncMode = request.getSyncMode();

        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("Task ID is required");
        }

        Optional<SyncTaskConfig> taskOpt = configManager.getTask(taskId);
        if (!taskOpt.isPresent()) {
            throw new NoSuchElementException("Task not found: " + taskId);
        }

        SyncTaskConfig task = taskOpt.get();

        if (!Boolean.TRUE.equals(task.getEnabled())) {
            throw new IllegalStateException("Task is disabled: " + taskId);
        }

        if (isTaskRunning(taskId)) {
            throw new IllegalStateException("Task is already running: " + taskId);
        }

        if (pausedTasks.contains(taskId)) {
            throw new IllegalStateException("Task is paused: " + taskId);
        }

        return doExecuteSync(task, syncMode);
    }

    @Override
    public SyncRecord executeSync(String taskId) {
        SyncExecuteRequest request = new SyncExecuteRequest();
        request.setTaskId(taskId);
        request.setSyncMode(Constants.SYNC_MODE_MANUAL);
        return executeSync(request);
    }

    @Override
    @Async
    public void executeScheduledSync(String taskId) {
        try {
            Optional<SyncTaskConfig> taskOpt = configManager.getTask(taskId);
            if (taskOpt.isPresent() && Boolean.TRUE.equals(taskOpt.get().getEnabled())) {
                doExecuteSync(taskOpt.get(), Constants.SYNC_MODE_SCHEDULED);
            }
        } catch (Exception e) {
            logger.error("Scheduled sync failed for task: {}", taskId, e);
        }
    }

    private SyncRecord doExecuteSync(SyncTaskConfig task, String syncMode) {
        String taskId = task.getTaskId();
        runningTasks.add(taskId);

        SyncRecord record = new SyncRecord();
        record.setTaskId(taskId);
        record.setSyncMode(syncMode);
        statusMonitor.recordSync(record);

        String syncId = record.getSyncId();
        activeSyncRecords.put(syncId, record);

        syncLogger.info(taskId, syncId, "Starting sync execution...");

        ConflictStrategyConfig strategyConfig = configManager.getOrCreateConflictStrategy(
                taskId,
                task.getConflictStrategy()
        );

        try {
            Optional<DataSourceConfig> sourceOpt = configManager.getDataSource(task.getSourceId());
            Optional<DataSourceConfig> targetOpt = configManager.getDataSource(task.getTargetId());

            if (!sourceOpt.isPresent()) {
                throw new Exception("Source data source not found: " + task.getSourceId());
            }
            if (!targetOpt.isPresent()) {
                throw new Exception("Target data source not found: " + task.getTargetId());
            }

            DataSourceConfig sourceConfig = sourceOpt.get();
            DataSourceConfig targetConfig = targetOpt.get();

            syncLogger.info(taskId, syncId, "Connecting to source data source: " + sourceConfig.getSourceId());
            DataSourceAdapter sourceAdapter = adapterFactory.getAdapter(sourceConfig);

            syncLogger.info(taskId, syncId, "Connecting to target data source: " + targetConfig.getSourceId());
            DataSourceAdapter targetAdapter = adapterFactory.getAdapter(targetConfig);

            String dataKeyField = task.getDataKeyField() != null ? task.getDataKeyField() : "id";
            String versionField = task.getVersionField();

            String tableName = extractTableName(task.getFilterRule());
            if (tableName == null) {
                tableName = "default_table";
            }

            List<Map<String, Object>> sourceData;

            if (asyncDataReader != null) {
                syncLogger.info(taskId, syncId, "Submitting async read request...");
                DataReadTask readTask = asyncDataReader.submitReadTask(
                        taskId,
                        syncId,
                        task.getSourceId(),
                        tableName,
                        extractFilterCondition(task.getFilterRule()),
                        dataKeyField,
                        task.getBatchSize() != null ? task.getBatchSize() : 1000,
                        0
                );

                syncLogger.info(taskId, syncId, "Read task submitted: " + readTask.getReadTaskId());

                sourceData = asyncDataReader.waitForData(readTask.getReadTaskId(), 300000);
                syncLogger.info(taskId, syncId, "Read completed: " + sourceData.size() + " records");
            } else {
                syncLogger.info(taskId, syncId, "Reading data from source (synchronous mode)...");
                sourceData = sourceAdapter.readData(
                        tableName,
                        extractFilterCondition(task.getFilterRule()),
                        dataKeyField
                );
            }

            record.setSourceRecords(sourceData.size());
            statusMonitor.updateProgress(syncId, sourceData.size(), 0, 0);

            syncLogger.info(taskId, syncId, "Read " + sourceData.size() + " records from source");

            int syncedCount = 0;
            int conflictCount = 0;

            for (Map<String, Object> sourceRecord : sourceData) {
                try {
                    String dataKey = String.valueOf(sourceRecord.get(dataKeyField));
                    syncLogger.info(taskId, syncId, "Processing record: " + dataKey, dataKey);

                    String sourceVersionStr = versionField != null && sourceRecord.containsKey(versionField)
                            ? String.valueOf(sourceRecord.get(versionField))
                            : versionManager.generateVersion(sourceRecord);
                    String sourceChecksum = versionManager.calculateChecksum(sourceRecord);

                    DataVersion sourceVersion = new DataVersion();
                    sourceVersion.setDataSource(task.getSourceId());
                    sourceVersion.setDataKey(dataKey);
                    sourceVersion.setVersion(sourceVersionStr);
                    sourceVersion.setChecksum(sourceChecksum);
                    sourceVersion.setSyncId(syncId);
                    sourceVersion.setTaskId(taskId);

                    boolean targetExists = targetAdapter.exists(tableName, dataKeyField, dataKey);
                    Map<String, Object> targetRecord = targetExists
                            ? targetAdapter.readSingle(tableName, dataKeyField, dataKey)
                            : null;

                    DataVersion targetVersion = versionManager.getVersion(task.getTargetId(), dataKey).orElse(null);

                    if (!targetExists || targetVersion == null) {
                        syncLogger.info(taskId, syncId, "New record, writing to target: " + dataKey, dataKey);
                        targetAdapter.writeData(tableName, dataKeyField, sourceRecord);
                        versionManager.updateVersion(task.getTargetId(), dataKey, sourceVersionStr, sourceChecksum);
                        syncedCount++;
                    } else {
                        if (versionManager.compareVersions(sourceVersion, targetVersion)) {
                            syncLogger.info(taskId, syncId, "Versions match, skipping: " + dataKey, dataKey);
                            continue;
                        }

                        ConflictRecord conflict = conflictDetector.detectConflict(
                                syncId, taskId, dataKey,
                                sourceRecord, targetRecord,
                                sourceVersion, targetVersion
                        );

                        if (conflict != null) {
                            conflictCount++;
                            syncLogger.warn(taskId, syncId, String.format(
                                    "Conflict detected for: %s (type: %s, priority: %d)",
                                    dataKey, conflict.getConflictType(), conflict.getPriority()), dataKey);

                            ConflictRecord resolved = conflictHandler.handleConflictWithConfig(
                                    conflict, strategyConfig
                            );

                            if (Constants.CONFLICT_STATUS_RESOLVED.equals(resolved.getStatus()) ||
                                Constants.CONFLICT_STATUS_AUTO_RESOLVED.equals(resolved.getStatus())) {
                                if (Constants.CONFLICT_STRATEGY_SOURCE_PRIORITY.equals(resolved.getResolution()) ||
                                    Constants.CONFLICT_STRATEGY_MERGE.equals(resolved.getResolution())) {
                                    Map<String, Object> dataToWrite = resolved.getSourceValue() != null
                                            ? resolved.getSourceValue()
                                            : sourceRecord;
                                    targetAdapter.updateData(tableName, dataKeyField, dataKey, dataToWrite);
                                    versionManager.updateVersion(task.getTargetId(), dataKey, sourceVersionStr, sourceChecksum);
                                }
                                syncedCount++;
                                syncLogger.info(taskId, syncId, String.format(
                                        "Conflict resolved for: %s (strategy: %s)",
                                        dataKey, resolved.getResolution()), dataKey);
                            } else if (Constants.CONFLICT_STRATEGY_TARGET_PRIORITY.equals(resolved.getResolution())) {
                                syncedCount++;
                                syncLogger.info(taskId, syncId, String.format(
                                        "Conflict resolved with target priority: %s", dataKey), dataKey);
                            } else if (Constants.CONFLICT_STATUS_MANUAL_REQUIRED.equals(resolved.getStatus())) {
                                syncLogger.warn(taskId, syncId, String.format(
                                        "Conflict requires manual resolution: %s (type: %s)",
                                        dataKey, conflict.getConflictType()), dataKey);
                            }
                        } else {
                            syncLogger.info(taskId, syncId, "Updating existing record: " + dataKey, dataKey);
                            targetAdapter.updateData(tableName, dataKeyField, dataKey, sourceRecord);
                            versionManager.updateVersion(task.getTargetId(), dataKey, sourceVersionStr, sourceChecksum);
                            syncedCount++;
                        }
                    }

                    statusMonitor.updateProgress(syncId, sourceData.size(), syncedCount, conflictCount);

                } catch (Exception e) {
                    syncLogger.error(taskId, syncId, "Error processing record: " + e.getMessage(),
                            null, e);
                    record.addError(e.getMessage());
                }
            }

            statusMonitor.markComplete(syncId);
            syncLogger.info(taskId, syncId, String.format(
                    "Sync completed: source=%d, synced=%d, conflicts=%d",
                    sourceData.size(), syncedCount, conflictCount));

        } catch (Exception e) {
            logger.error("Sync failed for task: {}", taskId, e);
            statusMonitor.markFailed(syncId, e.getMessage());
            syncLogger.error(taskId, syncId, "Sync failed: " + e.getMessage(), null, e);
            record.addError(e.getMessage());

            if (retryScheduler != null && Constants.SYNC_MODE_SCHEDULED.equals(syncMode)) {
                try {
                    retryScheduler.scheduleRetry(taskId, syncId, task.getMaxRetries(), e);
                } catch (Exception retryEx) {
                    logger.error("Failed to schedule retry for sync: {}", syncId, retryEx);
                }
            }
        } finally {
            runningTasks.remove(taskId);
            activeSyncRecords.remove(syncId);
        }

        return record;
    }

    private String extractTableName(String filterRule) {
        if (filterRule == null || filterRule.isEmpty()) {
            return null;
        }
        String[] parts = filterRule.split("\\|");
        if (parts.length > 0 && !parts[0].trim().isEmpty()) {
            return parts[0].trim();
        }
        return null;
    }

    private String extractFilterCondition(String filterRule) {
        if (filterRule == null || filterRule.isEmpty()) {
            return null;
        }
        String[] parts = filterRule.split("\\|");
        if (parts.length > 1 && !parts[1].trim().isEmpty()) {
            return parts[1].trim();
        }
        return null;
    }

    @Override
    public void pauseTask(String taskId) {
        pausedTasks.add(taskId);
        logger.info("Task paused: {}", taskId);
    }

    @Override
    public void resumeTask(String taskId) {
        pausedTasks.remove(taskId);
        logger.info("Task resumed: {}", taskId);
    }

    @Override
    public boolean isTaskRunning(String taskId) {
        return runningTasks.contains(taskId);
    }
}
