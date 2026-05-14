package com.datasync.service.retry.impl;

import com.datasync.common.Constants;
import com.datasync.dto.SyncExecuteRequest;
import com.datasync.model.RetryFailureDetail;
import com.datasync.model.RetryRecord;
import com.datasync.model.SyncRecord;
import com.datasync.model.SyncTaskConfig;
import com.datasync.service.config.ConfigManager;
import com.datasync.service.engine.SyncEngine;
import com.datasync.service.log.SyncLogger;
import com.datasync.service.monitor.StatusMonitor;
import com.datasync.service.retry.RetryFailureManager;
import com.datasync.service.retry.RetryScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RetrySchedulerImpl implements RetryScheduler {

    private static final Logger logger = LoggerFactory.getLogger(RetrySchedulerImpl.class);

    public static final String REDIS_KEY_PREFIX_RETRY = "retry:";

    private final Map<String, RetryRecord> retryCache = new ConcurrentHashMap<>();

    @Autowired
    private ConfigManager configManager;

    @Autowired
    private SyncEngine syncEngine;

    @Autowired
    private StatusMonitor statusMonitor;

    @Autowired
    private SyncLogger syncLogger;

    @Autowired
    private RetryFailureManager failureManager;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        loadAllRetriesFromPersistence();
    }

    @Override
    public void loadAllRetriesFromPersistence() {
        try {
            Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX_RETRY + "*");
            if (keys != null && !keys.isEmpty()) {
                int loaded = 0;
                for (String key : keys) {
                    try {
                        String json = redisTemplate.opsForValue().get(key);
                        if (json != null) {
                            RetryRecord retry = objectMapper.readValue(json, RetryRecord.class);
                            if ("pending".equals(retry.getStatus()) && retry.canRetry()) {
                                retryCache.put(retry.getSyncId(), retry);
                                loaded++;
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to load retry from Redis: {}", key, e);
                    }
                }
                logger.info("Loaded {} pending retries from persistence", loaded);
            }
        } catch (Exception e) {
            logger.warn("Failed to load retries from Redis", e);
        }
    }

    @Override
    public RetryRecord scheduleRetry(SyncRecord failedRecord, SyncTaskConfig task) {
        String syncId = failedRecord.getSyncId();
        String taskId = task.getTaskId();
        int maxRetries = task.getRetryCount() != null ? task.getRetryCount() : Constants.DEFAULT_RETRY_COUNT;

        return scheduleRetry(syncId, taskId, maxRetries);
    }

    @Override
    public RetryRecord scheduleRetry(String syncId, String taskId, int maxRetries) {
        if (retryCache.containsKey(syncId)) {
            RetryRecord existing = retryCache.get(syncId);
            logger.warn("Retry already exists for sync: {}, current attempt: {}", syncId, existing.getRetryCount());
            return existing;
        }

        if (maxRetries <= 0) {
            logger.info("Max retries set to 0, not scheduling retry for sync: {}", syncId);
            return null;
        }

        RetryRecord retryRecord = new RetryRecord();
        retryRecord.setSyncId(syncId);
        retryRecord.setTaskId(taskId);
        retryRecord.setMaxRetries(maxRetries);
        retryRecord.setNextRetryTime(System.currentTimeMillis() + Constants.DEFAULT_RETRY_INTERVAL);
        retryRecord.setStatus("pending");

        Map<String, Object> retryConfig = new LinkedHashMap<>();
        retryConfig.put("maxRetries", maxRetries);
        retryConfig.put("baseIntervalMs", Constants.DEFAULT_RETRY_INTERVAL);
        retryConfig.put("exponentialBackoff", true);
        retryRecord.setRetryConfig(retryConfig);

        retryCache.put(syncId, retryRecord);
        statusMonitor.incrementRetry(syncId);
        persistRetryRecord(retryRecord);

        logger.info("Scheduled retry for sync: {} (task: {}, max retries: {})",
                syncId, taskId, maxRetries);
        syncLogger.warn(taskId, syncId,
                String.format("Scheduled retry (max %d attempts, first attempt in %dms)",
                        maxRetries, Constants.DEFAULT_RETRY_INTERVAL));

        return retryRecord;
    }

    @Override
    @Async
    public Optional<SyncRecord> retrySync(String syncId) {
        RetryRecord retryRecord = retryCache.get(syncId);
        if (retryRecord == null) {
            logger.debug("No pending retry found for sync: {}", syncId);
            return Optional.empty();
        }
        return retrySync(retryRecord);
    }

    @Override
    @Async
    public Optional<SyncRecord> retrySync(RetryRecord retryRecord) {
        String syncId = retryRecord.getSyncId();
        String taskId = retryRecord.getTaskId();

        if (!retryRecord.canRetry()) {
            logger.error("Max retries exceeded for sync: {}, attempts: {}", syncId, retryRecord.getRetryCount());
            handleRetryExhausted(retryRecord, null, null);
            return Optional.empty();
        }

        try {
            retryRecord.incrementRetryCount();
            retryRecord.setStatus("running");
            statusMonitor.incrementRetry(syncId);
            persistRetryRecord(retryRecord);

            int currentAttempt = retryRecord.getRetryCount();
            int maxAttempts = retryRecord.getMaxRetries();

            logger.info("Executing retry {} of {} for sync: {}",
                    currentAttempt, maxAttempts, syncId);

            Optional<SyncTaskConfig> taskOpt = configManager.getTask(taskId);
            if (!taskOpt.isPresent()) {
                throw new Exception("Task not found: " + taskId);
            }

            SyncTaskConfig task = taskOpt.get();
            SyncExecuteRequest request = new SyncExecuteRequest();
            request.setTaskId(taskId);
            request.setSyncMode(Constants.SYNC_MODE_MANUAL);

            SyncRecord result = syncEngine.executeSync(request);

            if (Constants.SYNC_STATUS_COMPLETED.equals(result.getStatus())) {
                logger.info("Retry successful for sync: {} after {} attempts",
                        syncId, currentAttempt);

                syncLogger.info(taskId, syncId,
                        String.format("Retry successful after %d of %d attempts",
                                currentAttempt, maxAttempts));

                handleRetrySuccess(retryRecord);
                return Optional.of(result);
            } else {
                throw new Exception("Retry failed with status: " + result.getStatus());
            }

        } catch (Exception e) {
            logger.error("Retry failed for sync: {}, attempt: {}",
                    syncId, retryRecord.getRetryCount(), e);

            retryRecord.addError(e.getMessage());
            retryRecord.setStatus("pending");

            failureManager.recordFailure(
                    taskId, syncId, retryRecord.getRetryId(),
                    retryRecord.getRetryCount(), e, null, null
            );

            if (!retryRecord.canRetry()) {
                handleRetryExhausted(retryRecord, e, null);
                return Optional.empty();
            }

            retryRecord.scheduleNextRetry();
            persistRetryRecord(retryRecord);

            long remainingTime = retryRecord.getNextRetryTime() - System.currentTimeMillis();
            if (remainingTime < 0) remainingTime = 0;

            syncLogger.warn(taskId, syncId,
                    String.format("Retry %d failed, next attempt in %dms. Remaining attempts: %d. Error: %s",
                            retryRecord.getRetryCount(),
                            remainingTime,
                            retryRecord.getMaxRetries() - retryRecord.getRetryCount(),
                            e.getMessage()));

            return Optional.empty();
        }
    }

    private void handleRetrySuccess(RetryRecord retryRecord) {
        retryRecord.markCompleted(true);
        retryCache.remove(retryRecord.getSyncId());
        persistRetryRecord(retryRecord);

        logger.info("Retry completed successfully: {}", retryRecord.getSyncId());
    }

    private void handleRetryExhausted(RetryRecord retryRecord, Throwable exception, String dataKey) {
        String syncId = retryRecord.getSyncId();
        String taskId = retryRecord.getTaskId();

        retryRecord.markExhausted();
        retryCache.remove(syncId);
        persistRetryRecord(retryRecord);

        if (exception != null || dataKey != null) {
            failureManager.recordFailure(
                    taskId, syncId, retryRecord.getRetryId(),
                    retryRecord.getRetryCount(), exception, dataKey, null
            );
        }

        String errorMsg = String.format(
                "Max retries exceeded after %d attempts. Final error: %s",
                retryRecord.getRetryCount(),
                exception != null ? exception.getMessage() : "Unknown"
        );

        statusMonitor.markFailed(syncId, errorMsg);

        syncLogger.error(taskId, syncId,
                String.format("Max retries exceeded after %d attempts. Task marked as failed.",
                        retryRecord.getRetryCount()));

        logger.error("Retry exhausted for sync: {} (task: {}) after {} attempts",
                syncId, taskId, retryRecord.getRetryCount());
    }

    private void persistRetryRecord(RetryRecord retryRecord) {
        try {
            String json = objectMapper.writeValueAsString(retryRecord);
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX_RETRY + retryRecord.getSyncId(), json);
            logger.debug("Persisted retry record: {}", retryRecord.getRetryId());
        } catch (Exception e) {
            logger.warn("Failed to persist retry record to Redis: {}", retryRecord.getRetryId(), e);
        }
    }

    @Override
    public void cancelRetry(String syncId) {
        RetryRecord removed = retryCache.remove(syncId);
        if (removed != null) {
            removed.setStatus("cancelled");
            persistRetryRecord(removed);
            logger.info("Cancelled retry for sync: {}", syncId);
            syncLogger.warn(removed.getTaskId(), syncId, "Retry cancelled by user");
        }
    }

    @Override
    public boolean hasPendingRetry(String taskId) {
        return retryCache.values().stream()
                .anyMatch(r -> taskId.equals(r.getTaskId()) && "pending".equals(r.getStatus()));
    }

    @Override
    public int getPendingRetryCount(String taskId) {
        return (int) retryCache.values().stream()
                .filter(r -> taskId.equals(r.getTaskId()) && "pending".equals(r.getStatus()))
                .count();
    }

    @Override
    public void clearRetries(String taskId) {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, RetryRecord> entry : retryCache.entrySet()) {
            if (taskId.equals(entry.getValue().getTaskId())) {
                toRemove.add(entry.getKey());
            }
        }
        for (String key : toRemove) {
            RetryRecord record = retryCache.remove(key);
            if (record != null) {
                record.setStatus("cancelled");
                persistRetryRecord(record);
            }
        }
        logger.info("Cleared {} retries for task: {}", toRemove.size(), taskId);
    }

    @Override
    public Optional<RetryRecord> getRetryRecord(String syncId) {
        RetryRecord cached = retryCache.get(syncId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX_RETRY + syncId);
            if (json != null) {
                RetryRecord retry = objectMapper.readValue(json, RetryRecord.class);
                return Optional.of(retry);
            }
        } catch (Exception e) {
            logger.warn("Failed to get retry record from Redis: {}", syncId, e);
        }
        return Optional.empty();
    }

    @Override
    public List<RetryRecord> getRetryRecordsByTask(String taskId) {
        List<RetryRecord> allRecords = new ArrayList<>();

        allRecords.addAll(retryCache.values().stream()
                .filter(r -> taskId.equals(r.getTaskId()))
                .collect(Collectors.toList()));

        try {
            Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX_RETRY + "*");
            if (keys != null) {
                for (String key : keys) {
                    try {
                        String json = redisTemplate.opsForValue().get(key);
                        if (json != null) {
                            RetryRecord record = objectMapper.readValue(json, RetryRecord.class);
                            if (taskId.equals(record.getTaskId()) &&
                                !allRecords.contains(record)) {
                                allRecords.add(record);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to load retry from Redis: {}", key, e);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load retries from Redis", e);
        }

        return allRecords.stream()
                .sorted(Comparator.comparing(RetryRecord::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<RetryRecord> getAllRetryRecords() {
        List<RetryRecord> allRecords = new ArrayList<>(retryCache.values());

        try {
            Set<String> keys = redisTemplate.keys(REDIS_KEY_PREFIX_RETRY + "*");
            if (keys != null) {
                for (String key : keys) {
                    try {
                        String json = redisTemplate.opsForValue().get(key);
                        if (json != null) {
                            RetryRecord record = objectMapper.readValue(json, RetryRecord.class);
                            if (!allRecords.contains(record)) {
                                allRecords.add(record);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to load retry from Redis: {}", key, e);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load retries from Redis", e);
        }

        return allRecords.stream()
                .sorted(Comparator.comparing(RetryRecord::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<RetryRecord> getPendingRetryRecords() {
        return retryCache.values().stream()
                .filter(r -> "pending".equals(r.getStatus()))
                .sorted(Comparator.comparing(RetryRecord::getCreatedAt))
                .collect(Collectors.toList());
    }

    @Override
    public List<RetryRecord> getCompletedRetryRecords() {
        return getAllRetryRecords().stream()
                .filter(r -> "success".equals(r.getStatus()))
                .sorted(Comparator.comparing(RetryRecord::getCompletedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<RetryRecord> getExhaustedRetryRecords() {
        return getAllRetryRecords().stream()
                .filter(r -> "exhausted".equals(r.getStatus()))
                .sorted(Comparator.comparing(RetryRecord::getCompletedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public int getTotalRetriesCount() {
        return getAllRetryRecords().size();
    }

    @Override
    public int getSuccessfulRetriesCount() {
        return (int) getAllRetryRecords().stream()
                .filter(r -> Boolean.TRUE.equals(r.getSuccessful()))
                .count();
    }

    @Override
    public int getExhaustedRetriesCount() {
        return (int) getAllRetryRecords().stream()
                .filter(r -> "exhausted".equals(r.getStatus()))
                .count();
    }

    @Scheduled(fixedRate = 5000)
    public void processPendingRetries() {
        long now = System.currentTimeMillis();
        List<String> toRetry = new ArrayList<>();

        for (Map.Entry<String, RetryRecord> entry : retryCache.entrySet()) {
            RetryRecord record = entry.getValue();
            if ("pending".equals(record.getStatus()) &&
                record.getNextRetryTime() != null &&
                record.getNextRetryTime() <= now) {
                toRetry.add(entry.getKey());
            }
        }

        for (String syncId : toRetry) {
            try {
                RetryRecord record = retryCache.get(syncId);
                if (record != null && record.canRetry()) {
                    retrySync(record);
                }
            } catch (Exception e) {
                logger.error("Error processing retry for sync: {}", syncId, e);
            }
        }
    }
}
