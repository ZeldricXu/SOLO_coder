package com.datasync.controller;

import com.datasync.common.Constants;
import com.datasync.dto.ApiResponse;
import com.datasync.dto.SyncExecuteRequest;
import com.datasync.dto.SyncStatusResponse;
import com.datasync.model.*;
import com.datasync.service.conflict.ConflictHandler;
import com.datasync.service.config.ConfigManager;
import com.datasync.service.engine.SyncEngine;
import com.datasync.service.log.SyncLogger;
import com.datasync.service.monitor.StatusMonitor;
import com.datasync.service.read.AsyncDataReader;
import com.datasync.service.retry.RetryFailureManager;
import com.datasync.service.retry.RetryScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class SyncController {

    private static final Logger logger = LoggerFactory.getLogger(SyncController.class);

    @Autowired
    private ConfigManager configManager;

    @Autowired
    private SyncEngine syncEngine;

    @Autowired
    private StatusMonitor statusMonitor;

    @Autowired
    private ConflictHandler conflictHandler;

    @Autowired
    private SyncLogger syncLogger;

    @Autowired
    private RetryScheduler retryScheduler;

    @Autowired(required = false)
    private RetryFailureManager failureManager;

    @Autowired(required = false)
    private AsyncDataReader asyncDataReader;

    @PostMapping("/datasources")
    public ApiResponse<DataSourceConfig> createDataSource(@RequestBody DataSourceConfig config) {
        try {
            DataSourceConfig saved = configManager.saveDataSource(config);
            return ApiResponse.success("Data source created successfully", saved);
        } catch (Exception e) {
            logger.error("Failed to create data source", e);
            return ApiResponse.error("Failed to create data source: " + e.getMessage());
        }
    }

    @GetMapping("/datasources")
    public ApiResponse<List<DataSourceConfig>> listDataSources() {
        try {
            List<DataSourceConfig> sources = configManager.getAllDataSources();
            return ApiResponse.success(sources);
        } catch (Exception e) {
            logger.error("Failed to list data sources", e);
            return ApiResponse.error("Failed to list data sources: " + e.getMessage());
        }
    }

    @GetMapping("/datasources/{sourceId}")
    public ApiResponse<DataSourceConfig> getDataSource(@PathVariable String sourceId) {
        try {
            Optional<DataSourceConfig> opt = configManager.getDataSource(sourceId);
            if (opt.isPresent()) {
                return ApiResponse.success(opt.get());
            }
            return ApiResponse.notFound("Data source not found: " + sourceId);
        } catch (Exception e) {
            logger.error("Failed to get data source", e);
            return ApiResponse.error("Failed to get data source: " + e.getMessage());
        }
    }

    @DeleteMapping("/datasources/{sourceId}")
    public ApiResponse<String> deleteDataSource(@PathVariable String sourceId) {
        try {
            boolean deleted = configManager.deleteDataSource(sourceId);
            if (deleted) {
                return ApiResponse.success("Data source deleted successfully", sourceId);
            }
            return ApiResponse.notFound("Data source not found: " + sourceId);
        } catch (IllegalStateException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to delete data source", e);
            return ApiResponse.error("Failed to delete data source: " + e.getMessage());
        }
    }

    @PostMapping("/sync/tasks")
    public ApiResponse<Map<String, String>> createTask(@RequestBody SyncTaskConfig config) {
        try {
            SyncTaskConfig saved = configManager.saveTask(config);
            Map<String, String> result = new LinkedHashMap<>();
            result.put("task_id", saved.getTaskId());
            return ApiResponse.success("Task created successfully", result);
        } catch (Exception e) {
            logger.error("Failed to create task", e);
            return ApiResponse.error("Failed to create task: " + e.getMessage());
        }
    }

    @GetMapping("/sync/tasks")
    public ApiResponse<List<SyncTaskConfig>> listTasks() {
        try {
            List<SyncTaskConfig> tasks = configManager.getAllTasks();
            return ApiResponse.success(tasks);
        } catch (Exception e) {
            logger.error("Failed to list tasks", e);
            return ApiResponse.error("Failed to list tasks: " + e.getMessage());
        }
    }

    @GetMapping("/sync/tasks/{taskId}")
    public ApiResponse<SyncTaskConfig> getTask(@PathVariable String taskId) {
        try {
            Optional<SyncTaskConfig> opt = configManager.getTask(taskId);
            if (opt.isPresent()) {
                return ApiResponse.success(opt.get());
            }
            return ApiResponse.notFound("Task not found: " + taskId);
        } catch (Exception e) {
            logger.error("Failed to get task", e);
            return ApiResponse.error("Failed to get task: " + e.getMessage());
        }
    }

    @DeleteMapping("/sync/tasks/{taskId}")
    public ApiResponse<String> deleteTask(@PathVariable String taskId) {
        try {
            boolean deleted = configManager.deleteTask(taskId);
            if (deleted) {
                return ApiResponse.success("Task deleted successfully", taskId);
            }
            return ApiResponse.notFound("Task not found: " + taskId);
        } catch (Exception e) {
            logger.error("Failed to delete task", e);
            return ApiResponse.error("Failed to delete task: " + e.getMessage());
        }
    }

    @PostMapping("/sync/execute")
    public ApiResponse<Map<String, Object>> executeSync(@RequestBody SyncExecuteRequest request) {
        try {
            SyncRecord record = syncEngine.executeSync(request);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sync_id", record.getSyncId());
            result.put("status", record.getStatus());
            return ApiResponse.success("Sync execution started", result);
        } catch (NoSuchElementException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.conflict(e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to execute sync", e);
            return ApiResponse.error("Failed to execute sync: " + e.getMessage());
        }
    }

    @GetMapping("/sync/status")
    public ApiResponse<SyncStatusResponse> getSyncStatus(
            @RequestParam(required = false) String task_id,
            @RequestParam(required = false) String sync_id,
            @RequestParam(required = false) Long start_time,
            @RequestParam(required = false) Long end_time) {

        try {
            SyncStatusResponse response = new SyncStatusResponse();

            if (sync_id != null && !sync_id.isEmpty()) {
                Optional<SyncRecord> recordOpt = statusMonitor.getSyncRecord(sync_id);
                if (recordOpt.isPresent()) {
                    response.setSyncRecords(Collections.singletonList(recordOpt.get()));
                    response.setConflicts(conflictHandler.getConflictsBySyncId(sync_id));
                }
            } else if (task_id != null && !task_id.isEmpty()) {
                response.setSyncRecords(statusMonitor.getSyncRecordsByTask(task_id));
                response.setConflicts(conflictHandler.getConflictsByTaskId(task_id));
                response.setTaskStatus(
                        syncEngine.isTaskRunning(task_id) ? "running" : "idle"
                );
            } else {
                response.setSyncRecords(statusMonitor.getRunningSyncs());
            }

            return ApiResponse.success(response);
        } catch (Exception e) {
            logger.error("Failed to get sync status", e);
            return ApiResponse.error("Failed to get sync status: " + e.getMessage());
        }
    }

    @GetMapping("/sync/records/{syncId}")
    public ApiResponse<SyncRecord> getSyncRecord(@PathVariable String syncId) {
        try {
            Optional<SyncRecord> opt = statusMonitor.getSyncRecord(syncId);
            if (opt.isPresent()) {
                return ApiResponse.success(opt.get());
            }
            return ApiResponse.notFound("Sync record not found: " + syncId);
        } catch (Exception e) {
            logger.error("Failed to get sync record", e);
            return ApiResponse.error("Failed to get sync record: " + e.getMessage());
        }
    }

    @GetMapping("/sync/logs/{syncId}")
    public ApiResponse<List<SyncLog>> getSyncLogs(@PathVariable String syncId) {
        try {
            List<SyncLog> logs = syncLogger.getLogsBySyncId(syncId);
            return ApiResponse.success(logs);
        } catch (Exception e) {
            logger.error("Failed to get sync logs", e);
            return ApiResponse.error("Failed to get sync logs: " + e.getMessage());
        }
    }

    @GetMapping("/conflicts")
    public ApiResponse<List<ConflictRecord>> listConflicts(
            @RequestParam(required = false) String task_id,
            @RequestParam(required = false) String status) {

        try {
            List<ConflictRecord> conflicts;
            if (task_id != null && !task_id.isEmpty()) {
                conflicts = conflictHandler.getConflictsByTaskId(task_id);
            } else if (status != null && !status.isEmpty()) {
                if ("pending".equals(status)) {
                    conflicts = conflictHandler.getPendingConflicts();
                } else {
                    conflicts = conflictHandler.getAllConflicts();
                    Iterator<ConflictRecord> it = conflicts.iterator();
                    while (it.hasNext()) {
                        if (!status.equals(it.next().getStatus())) {
                            it.remove();
                        }
                    }
                }
            } else {
                conflicts = conflictHandler.getAllConflicts();
            }
            return ApiResponse.success(conflicts);
        } catch (Exception e) {
            logger.error("Failed to list conflicts", e);
            return ApiResponse.error("Failed to list conflicts: " + e.getMessage());
        }
    }

    @GetMapping("/conflicts/{conflictId}")
    public ApiResponse<ConflictRecord> getConflict(@PathVariable String conflictId) {
        try {
            Optional<ConflictRecord> opt = conflictHandler.getConflict(conflictId);
            if (opt.isPresent()) {
                return ApiResponse.success(opt.get());
            }
            return ApiResponse.notFound("Conflict not found: " + conflictId);
        } catch (Exception e) {
            logger.error("Failed to get conflict", e);
            return ApiResponse.error("Failed to get conflict: " + e.getMessage());
        }
    }

    @PostMapping("/conflicts/{conflictId}/resolve")
    public ApiResponse<ConflictRecord> resolveConflict(
            @PathVariable String conflictId,
            @RequestBody Map<String, Object> resolution) {

        try {
            ConflictRecord resolved = conflictHandler.resolveManualConflict(conflictId, resolution);
            return ApiResponse.success("Conflict resolved successfully", resolved);
        } catch (NoSuchElementException e) {
            return ApiResponse.notFound(e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to resolve conflict", e);
            return ApiResponse.error("Failed to resolve conflict: " + e.getMessage());
        }
    }

    @GetMapping("/conflicts/sorted")
    public ApiResponse<List<ConflictRecord>> listConflictsSortedByPriority() {
        try {
            List<ConflictRecord> conflicts = conflictHandler.getConflictsSortedByPriority();
            return ApiResponse.success(conflicts);
        } catch (Exception e) {
            logger.error("Failed to list conflicts by priority", e);
            return ApiResponse.error("Failed to list conflicts: " + e.getMessage());
        }
    }

    @GetMapping("/conflicts/type/{conflictType}")
    public ApiResponse<List<ConflictRecord>> listConflictsByType(@PathVariable String conflictType) {
        try {
            List<ConflictRecord> conflicts = conflictHandler.getConflictsByType(conflictType);
            return ApiResponse.success(conflicts);
        } catch (Exception e) {
            logger.error("Failed to list conflicts by type", e);
            return ApiResponse.error("Failed to list conflicts: " + e.getMessage());
        }
    }

    @GetMapping("/conflicts/priority/{priority}")
    public ApiResponse<List<ConflictRecord>> listConflictsByPriority(@PathVariable int priority) {
        try {
            List<ConflictRecord> conflicts = conflictHandler.getConflictsByPriority(priority);
            return ApiResponse.success(conflicts);
        } catch (Exception e) {
            logger.error("Failed to list conflicts by priority", e);
            return ApiResponse.error("Failed to list conflicts: " + e.getMessage());
        }
    }

    @PostMapping("/conflict-strategies")
    public ApiResponse<ConflictStrategyConfig> createConflictStrategy(@RequestBody ConflictStrategyConfig config) {
        try {
            ConflictStrategyConfig saved = configManager.saveConflictStrategy(config);
            return ApiResponse.success("Conflict strategy created successfully", saved);
        } catch (Exception e) {
            logger.error("Failed to create conflict strategy", e);
            return ApiResponse.error("Failed to create conflict strategy: " + e.getMessage());
        }
    }

    @GetMapping("/conflict-strategies")
    public ApiResponse<List<ConflictStrategyConfig>> listConflictStrategies() {
        try {
            List<ConflictStrategyConfig> strategies = configManager.getAllConflictStrategies();
            return ApiResponse.success(strategies);
        } catch (Exception e) {
            logger.error("Failed to list conflict strategies", e);
            return ApiResponse.error("Failed to list conflict strategies: " + e.getMessage());
        }
    }

    @GetMapping("/conflict-strategies/{configId}")
    public ApiResponse<ConflictStrategyConfig> getConflictStrategy(@PathVariable String configId) {
        try {
            Optional<ConflictStrategyConfig> opt = configManager.getConflictStrategy(configId);
            if (opt.isPresent()) {
                return ApiResponse.success(opt.get());
            }
            return ApiResponse.notFound("Conflict strategy not found: " + configId);
        } catch (Exception e) {
            logger.error("Failed to get conflict strategy", e);
            return ApiResponse.error("Failed to get conflict strategy: " + e.getMessage());
        }
    }

    @GetMapping("/conflict-strategies/task/{taskId}")
    public ApiResponse<ConflictStrategyConfig> getConflictStrategyByTask(@PathVariable String taskId) {
        try {
            Optional<ConflictStrategyConfig> opt = configManager.getConflictStrategyByTask(taskId);
            if (opt.isPresent()) {
                return ApiResponse.success(opt.get());
            }
            SyncTaskConfig taskConfig = configManager.getTask(taskId).orElse(null);
            String defaultStrategy = taskConfig != null ? taskConfig.getConflictStrategy() : null;
            ConflictStrategyConfig created = configManager.getOrCreateConflictStrategy(taskId, defaultStrategy);
            return ApiResponse.success("Created default strategy for task", created);
        } catch (Exception e) {
            logger.error("Failed to get conflict strategy by task", e);
            return ApiResponse.error("Failed to get conflict strategy: " + e.getMessage());
        }
    }

    @DeleteMapping("/conflict-strategies/{configId}")
    public ApiResponse<String> deleteConflictStrategy(@PathVariable String configId) {
        try {
            boolean deleted = configManager.deleteConflictStrategy(configId);
            if (deleted) {
                return ApiResponse.success("Conflict strategy deleted successfully", configId);
            }
            return ApiResponse.notFound("Conflict strategy not found: " + configId);
        } catch (Exception e) {
            logger.error("Failed to delete conflict strategy", e);
            return ApiResponse.error("Failed to delete conflict strategy: " + e.getMessage());
        }
    }

    @GetMapping("/retries")
    public ApiResponse<List<RetryRecord>> listRetries(
            @RequestParam(required = false) String task_id,
            @RequestParam(required = false) String status) {

        try {
            List<RetryRecord> retries;
            if (task_id != null && !task_id.isEmpty()) {
                retries = retryScheduler.getRetryRecordsByTask(task_id);
            } else if ("pending".equals(status)) {
                retries = retryScheduler.getPendingRetryRecords();
            } else if ("completed".equals(status)) {
                retries = retryScheduler.getCompletedRetryRecords();
            } else if ("exhausted".equals(status)) {
                retries = retryScheduler.getExhaustedRetryRecords();
            } else {
                retries = retryScheduler.getAllRetryRecords();
            }
            return ApiResponse.success(retries);
        } catch (Exception e) {
            logger.error("Failed to list retries", e);
            return ApiResponse.error("Failed to list retries: " + e.getMessage());
        }
    }

    @GetMapping("/retries/{syncId}")
    public ApiResponse<RetryRecord> getRetryRecord(@PathVariable String syncId) {
        try {
            Optional<RetryRecord> opt = retryScheduler.getRetryRecord(syncId);
            if (opt.isPresent()) {
                return ApiResponse.success(opt.get());
            }
            return ApiResponse.notFound("Retry record not found: " + syncId);
        } catch (Exception e) {
            logger.error("Failed to get retry record", e);
            return ApiResponse.error("Failed to get retry record: " + e.getMessage());
        }
    }

    @DeleteMapping("/retries/{syncId}")
    public ApiResponse<String> cancelRetry(@PathVariable String syncId) {
        try {
            retryScheduler.cancelRetry(syncId);
            return ApiResponse.success("Retry cancelled successfully", syncId);
        } catch (Exception e) {
            logger.error("Failed to cancel retry", e);
            return ApiResponse.error("Failed to cancel retry: " + e.getMessage());
        }
    }

    @GetMapping("/retries/failures")
    public ApiResponse<List<RetryFailureDetail>> listRetryFailures(
            @RequestParam(required = false) String task_id,
            @RequestParam(required = false) String sync_id,
            @RequestParam(required = false) String failure_type) {

        if (failureManager == null) {
            return ApiResponse.error("Retry failure manager not available");
        }

        try {
            List<RetryFailureDetail> failures;
            if (sync_id != null && !sync_id.isEmpty()) {
                failures = failureManager.getFailuresBySync(sync_id);
            } else if (task_id != null && !task_id.isEmpty()) {
                failures = failureManager.getFailuresByTask(task_id);
            } else if (failure_type != null && !failure_type.isEmpty()) {
                failures = failureManager.getFailuresByType(failure_type);
            } else {
                failures = failureManager.getAllFailures();
            }
            return ApiResponse.success(failures);
        } catch (Exception e) {
            logger.error("Failed to list retry failures", e);
            return ApiResponse.error("Failed to list retry failures: " + e.getMessage());
        }
    }

    @GetMapping("/retries/failures/{failureId}")
    public ApiResponse<RetryFailureDetail> getRetryFailure(@PathVariable String failureId) {
        if (failureManager == null) {
            return ApiResponse.error("Retry failure manager not available");
        }

        try {
            Optional<RetryFailureDetail> opt = failureManager.getFailure(failureId);
            if (opt.isPresent()) {
                return ApiResponse.success(opt.get());
            }
            return ApiResponse.notFound("Retry failure not found: " + failureId);
        } catch (Exception e) {
            logger.error("Failed to get retry failure", e);
            return ApiResponse.error("Failed to get retry failure: " + e.getMessage());
        }
    }

    @PostMapping("/retries/failures/{failureId}/resolve")
    public ApiResponse<RetryFailureDetail> resolveRetryFailure(@PathVariable String failureId) {
        if (failureManager == null) {
            return ApiResponse.error("Retry failure manager not available");
        }

        try {
            Optional<RetryFailureDetail> opt = failureManager.resolveFailure(failureId);
            if (opt.isPresent()) {
                return ApiResponse.success("Failure resolved", opt.get());
            }
            return ApiResponse.notFound("Retry failure not found: " + failureId);
        } catch (Exception e) {
            logger.error("Failed to resolve retry failure", e);
            return ApiResponse.error("Failed to resolve retry failure: " + e.getMessage());
        }
    }

    @PostMapping("/retries/failures/{failureId}/ignore")
    public ApiResponse<RetryFailureDetail> ignoreRetryFailure(@PathVariable String failureId) {
        if (failureManager == null) {
            return ApiResponse.error("Retry failure manager not available");
        }

        try {
            Optional<RetryFailureDetail> opt = failureManager.ignoreFailure(failureId);
            if (opt.isPresent()) {
                return ApiResponse.success("Failure ignored", opt.get());
            }
            return ApiResponse.notFound("Retry failure not found: " + failureId);
        } catch (Exception e) {
            logger.error("Failed to ignore retry failure", e);
            return ApiResponse.error("Failed to ignore retry failure: " + e.getMessage());
        }
    }

    @GetMapping("/retries/failures/statistics")
    public ApiResponse<Map<String, Object>> getRetryFailureStatistics() {
        if (failureManager == null) {
            return ApiResponse.error("Retry failure manager not available");
        }

        try {
            Map<String, Object> stats = failureManager.getStatistics();
            return ApiResponse.success(stats);
        } catch (Exception e) {
            logger.error("Failed to get retry failure statistics", e);
            return ApiResponse.error("Failed to get retry failure statistics: " + e.getMessage());
        }
    }

    @PostMapping("/read/tasks")
    public ApiResponse<DataReadTask> submitReadTask(@RequestBody DataReadTask task) {
        if (asyncDataReader == null) {
            return ApiResponse.error("Async data reader not available");
        }

        try {
            DataReadTask submitted = asyncDataReader.submitReadTask(task);
            return ApiResponse.success("Read task submitted", submitted);
        } catch (Exception e) {
            logger.error("Failed to submit read task", e);
            return ApiResponse.error("Failed to submit read task: " + e.getMessage());
        }
    }

    @GetMapping("/read/tasks")
    public ApiResponse<List<DataReadTask>> listReadTasks(
            @RequestParam(required = false) String task_id,
            @RequestParam(required = false) String sync_id,
            @RequestParam(required = false) String status) {

        if (asyncDataReader == null) {
            return ApiResponse.error("Async data reader not available");
        }

        try {
            List<DataReadTask> tasks;
            if (sync_id != null && !sync_id.isEmpty()) {
                tasks = asyncDataReader.getReadTasksBySync(sync_id);
            } else if (task_id != null && !task_id.isEmpty()) {
                tasks = asyncDataReader.getReadTasksByTask(task_id);
            } else if ("pending".equals(status)) {
                tasks = asyncDataReader.getPendingReadTasks();
            } else if ("running".equals(status)) {
                tasks = asyncDataReader.getRunningReadTasks();
            } else if ("completed".equals(status)) {
                tasks = asyncDataReader.getCompletedReadTasks();
            } else {
                tasks = new ArrayList<>();
                tasks.addAll(asyncDataReader.getPendingReadTasks());
                tasks.addAll(asyncDataReader.getRunningReadTasks());
                tasks.addAll(asyncDataReader.getCompletedReadTasks());
            }
            return ApiResponse.success(tasks);
        } catch (Exception e) {
            logger.error("Failed to list read tasks", e);
            return ApiResponse.error("Failed to list read tasks: " + e.getMessage());
        }
    }

    @GetMapping("/read/tasks/{readTaskId}")
    public ApiResponse<DataReadTask> getReadTask(@PathVariable String readTaskId) {
        if (asyncDataReader == null) {
            return ApiResponse.error("Async data reader not available");
        }

        try {
            Optional<DataReadTask> opt = asyncDataReader.getReadTask(readTaskId);
            if (opt.isPresent()) {
                return ApiResponse.success(opt.get());
            }
            return ApiResponse.notFound("Read task not found: " + readTaskId);
        } catch (Exception e) {
            logger.error("Failed to get read task", e);
            return ApiResponse.error("Failed to get read task: " + e.getMessage());
        }
    }

    @DeleteMapping("/read/tasks/{readTaskId}")
    public ApiResponse<String> cancelReadTask(@PathVariable String readTaskId) {
        if (asyncDataReader == null) {
            return ApiResponse.error("Async data reader not available");
        }

        try {
            asyncDataReader.cancelReadTask(readTaskId);
            return ApiResponse.success("Read task cancelled", readTaskId);
        } catch (Exception e) {
            logger.error("Failed to cancel read task", e);
            return ApiResponse.error("Failed to cancel read task: " + e.getMessage());
        }
    }

    @GetMapping("/read/tasks/{readTaskId}/data")
    public ApiResponse<List<Map<String, Object>>> getReadTaskData(
            @PathVariable String readTaskId,
            @RequestParam(required = false, defaultValue = "60000") long timeout_ms) {

        if (asyncDataReader == null) {
            return ApiResponse.error("Async data reader not available");
        }

        try {
            List<Map<String, Object>> data = asyncDataReader.waitForData(readTaskId, timeout_ms);
            return ApiResponse.success(data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApiResponse.error("Read task operation interrupted");
        } catch (Exception e) {
            logger.error("Failed to get read task data", e);
            return ApiResponse.error("Failed to get read task data: " + e.getMessage());
        }
    }

    @GetMapping("/read/statistics")
    public ApiResponse<Map<String, Object>> getReadTaskStatistics() {
        if (asyncDataReader == null) {
            return ApiResponse.error("Async data reader not available");
        }

        try {
            Map<String, Object> stats = asyncDataReader.getReadTaskStatistics();
            return ApiResponse.success(stats);
        } catch (Exception e) {
            logger.error("Failed to get read task statistics", e);
            return ApiResponse.error("Failed to get read task statistics: " + e.getMessage());
        }
    }

    @GetMapping("/sync/tasks/{taskId}/status")
    public ApiResponse<Map<String, Object>> getTaskDetailedStatus(@PathVariable String taskId) {
        try {
            Map<String, Object> status = statusMonitor.getTaskStatus(taskId);
            return ApiResponse.success(status);
        } catch (Exception e) {
            logger.error("Failed to get task status", e);
            return ApiResponse.error("Failed to get task status: " + e.getMessage());
        }
    }

    @GetMapping("/sync/history/{taskId}")
    public ApiResponse<List<SyncRecord>> getSyncHistory(
            @PathVariable String taskId,
            @RequestParam(required = false, defaultValue = "50") int limit) {

        try {
            List<SyncRecord> records = statusMonitor.getSyncRecordsByTask(taskId, limit);
            return ApiResponse.success(records);
        } catch (Exception e) {
            logger.error("Failed to get sync history", e);
            return ApiResponse.error("Failed to get sync history: " + e.getMessage());
        }
    }

    @GetMapping("/statistics")
    public ApiResponse<Map<String, Object>> getStatistics() {
        try {
            Map<String, Object> stats = new LinkedHashMap<>();

            Map<String, Object> syncStats = statusMonitor.getStatistics();
            stats.put("sync", syncStats);

            Map<String, Object> conflictStats = new LinkedHashMap<>();
            conflictStats.put("total", conflictHandler.getAllConflicts().size());
            conflictStats.put("pending", conflictHandler.getConflictCountByStatus(Constants.CONFLICT_STATUS_PENDING));
            conflictStats.put("resolved", conflictHandler.getConflictCountByStatus(Constants.CONFLICT_STATUS_RESOLVED));
            conflictStats.put("auto_resolved", conflictHandler.getConflictCountByStatus(Constants.CONFLICT_STATUS_AUTO_RESOLVED));
            conflictStats.put("manual_required", conflictHandler.getConflictCountByStatus(Constants.CONFLICT_STATUS_MANUAL_REQUIRED));
            conflictStats.put("version_conflicts", conflictHandler.getConflictCountByType(Constants.CONFLICT_TYPE_VERSION));
            conflictStats.put("content_conflicts", conflictHandler.getConflictCountByType(Constants.CONFLICT_TYPE_CONTENT));
            conflictStats.put("structure_conflicts", conflictHandler.getConflictCountByType(Constants.CONFLICT_TYPE_STRUCTURE));
            conflictStats.put("type_mismatch_conflicts", conflictHandler.getConflictCountByType(Constants.CONFLICT_TYPE_TYPE_MISMATCH));
            conflictStats.put("mixed_conflicts", conflictHandler.getConflictCountByType(Constants.CONFLICT_TYPE_MIXED));
            conflictStats.put("critical_priority", conflictHandler.getConflictCountByPriority(Constants.CONFLICT_PRIORITY_CRITICAL));
            conflictStats.put("high_priority", conflictHandler.getConflictCountByPriority(Constants.CONFLICT_PRIORITY_HIGH));
            conflictStats.put("medium_priority", conflictHandler.getConflictCountByPriority(Constants.CONFLICT_PRIORITY_MEDIUM));
            conflictStats.put("low_priority", conflictHandler.getConflictCountByPriority(Constants.CONFLICT_PRIORITY_LOW));
            stats.put("conflicts", conflictStats);

            Map<String, Object> retryStats = new LinkedHashMap<>();
            retryStats.put("total", retryScheduler.getTotalRetriesCount());
            retryStats.put("successful", retryScheduler.getSuccessfulRetriesCount());
            retryStats.put("exhausted", retryScheduler.getExhaustedRetriesCount());
            retryStats.put("pending", retryScheduler.getPendingRetryRecords().size());
            stats.put("retries", retryStats);

            if (failureManager != null) {
                stats.put("retry_failures", failureManager.getStatistics());
            }

            if (asyncDataReader != null) {
                stats.put("async_read", asyncDataReader.getReadTaskStatistics());
            }

            stats.put("strategies", configManager.getAllConflictStrategies().size());

            return ApiResponse.success(stats);
        } catch (Exception e) {
            logger.error("Failed to get statistics", e);
            return ApiResponse.error("Failed to get statistics: " + e.getMessage());
        }
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        try {
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("status", "UP");
            health.put("running_syncs", statusMonitor.getRunningSyncCount());
            health.put("failed_syncs", statusMonitor.getFailedSyncCount());
            health.put("completed_syncs", statusMonitor.getCompletedSyncCount());
            health.put("total_syncs", statusMonitor.getTotalSyncCount());
            health.put("pending_conflicts", conflictHandler.getConflictCountByStatus(Constants.CONFLICT_STATUS_PENDING));
            health.put("critical_conflicts", conflictHandler.getConflictCountByPriority(Constants.CONFLICT_PRIORITY_CRITICAL));
            health.put("pending_retries", retryScheduler.getPendingRetryRecords().size());
            health.put("total_tasks", configManager.getAllTasks().size());
            health.put("enabled_tasks", configManager.getEnabledTasks().size());
            health.put("total_strategies", configManager.getAllConflictStrategies().size());

            if (asyncDataReader != null) {
                health.put("async_reader_active", true);
                health.put("read_queue_size", asyncDataReader.getQueueSize());
                health.put("read_active_workers", asyncDataReader.getActiveWorkerCount());
            } else {
                health.put("async_reader_active", false);
            }

            if (failureManager != null) {
                health.put("failure_manager_active", true);
            } else {
                health.put("failure_manager_active", false);
            }

            return ApiResponse.success(health);
        } catch (Exception e) {
            return ApiResponse.error("Health check failed: " + e.getMessage());
        }
    }
}
