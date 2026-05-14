package com.datasync.service.monitor.impl;

import com.datasync.common.Constants;
import com.datasync.model.SyncRecord;
import com.datasync.service.monitor.StatusMonitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class StatusMonitorImpl implements StatusMonitor {

    private static final Logger logger = LoggerFactory.getLogger(StatusMonitorImpl.class);

    private final Map<String, SyncRecord> syncRecords = new ConcurrentHashMap<>();
    private final Map<String, String> taskCurrentSyncId = new ConcurrentHashMap<>();

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        loadAllRecordsFromPersistence();
    }

    @Override
    public void loadAllRecordsFromPersistence() {
        try {
            Set<String> keys = redisTemplate.keys(Constants.REDIS_KEY_PREFIX_SYNC_RECORD + "*");
            if (keys != null && !keys.isEmpty()) {
                int loaded = 0;
                for (String key : keys) {
                    try {
                        String json = redisTemplate.opsForValue().get(key);
                        if (json != null) {
                            SyncRecord record = objectMapper.readValue(json, SyncRecord.class);
                            syncRecords.put(record.getSyncId(), record);
                            if (Constants.SYNC_STATUS_RUNNING.equals(record.getStatus()) ||
                                Constants.SYNC_STATUS_RETRYING.equals(record.getStatus())) {
                                taskCurrentSyncId.put(record.getTaskId(), record.getSyncId());
                            }
                            loaded++;
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to load sync record from Redis: {}", key, e);
                    }
                }
                logger.info("Loaded {} sync records from persistence", loaded);
            }
        } catch (Exception e) {
            logger.warn("Failed to load sync records from Redis", e);
        }
    }

    @Override
    public void updateStatus(String taskId, String syncId, String status) {
        SyncRecord record = syncRecords.get(syncId);
        if (record != null) {
            String oldStatus = record.getStatus();
            record.setStatus(status);
            saveToRedis(record);
            logger.info("Updated sync status: {} -> {} (was: {})", syncId, status, oldStatus);
        }
    }

    @Override
    public void updateProgress(String syncId, int sourceRecords, int syncedRecords, int conflictCount) {
        SyncRecord record = syncRecords.get(syncId);
        if (record != null) {
            record.setSourceRecords(sourceRecords);
            record.setSyncedRecords(syncedRecords);
            record.setConflictCount(conflictCount);
            saveToRedis(record);
        }
    }

    @Override
    public Optional<SyncRecord> getSyncRecord(String syncId) {
        SyncRecord cached = syncRecords.get(syncId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            String json = redisTemplate.opsForValue().get(Constants.REDIS_KEY_PREFIX_SYNC_RECORD + syncId);
            if (json != null) {
                SyncRecord record = objectMapper.readValue(json, SyncRecord.class);
                syncRecords.put(syncId, record);
                return Optional.of(record);
            }
        } catch (Exception e) {
            logger.warn("Failed to get sync record from Redis: {}", syncId, e);
        }
        return Optional.empty();
    }

    @Override
    public List<SyncRecord> getSyncRecordsByTask(String taskId) {
        return getSyncRecordsByTask(taskId, Integer.MAX_VALUE);
    }

    @Override
    public List<SyncRecord> getSyncRecordsByTask(String taskId, int limit) {
        return syncRecords.values().stream()
                .filter(r -> taskId.equals(r.getTaskId()))
                .sorted(Comparator.comparing(SyncRecord::getStartTime).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<SyncRecord> getSyncRecordsByTimeRange(Instant startTime, Instant endTime) {
        return syncRecords.values().stream()
                .filter(r -> {
                    Instant recordTime = r.getStartTime();
                    return recordTime != null &&
                           !recordTime.isBefore(startTime) &&
                           (endTime == null || !recordTime.isAfter(endTime));
                })
                .sorted(Comparator.comparing(SyncRecord::getStartTime).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<SyncRecord> getSyncRecordsByTimeRange(String taskId, Instant startTime, Instant endTime) {
        return syncRecords.values().stream()
                .filter(r -> taskId.equals(r.getTaskId()))
                .filter(r -> {
                    Instant recordTime = r.getStartTime();
                    return recordTime != null &&
                           !recordTime.isBefore(startTime) &&
                           (endTime == null || !recordTime.isAfter(endTime));
                })
                .sorted(Comparator.comparing(SyncRecord::getStartTime).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<SyncRecord> getRunningSyncs() {
        return syncRecords.values().stream()
                .filter(r -> Constants.SYNC_STATUS_RUNNING.equals(r.getStatus()))
                .collect(Collectors.toList());
    }

    @Override
    public List<SyncRecord> getFailedSyncs() {
        return syncRecords.values().stream()
                .filter(r -> Constants.SYNC_STATUS_FAILED.equals(r.getStatus()))
                .collect(Collectors.toList());
    }

    @Override
    public List<SyncRecord> getCompletedSyncs() {
        return syncRecords.values().stream()
                .filter(r -> Constants.SYNC_STATUS_COMPLETED.equals(r.getStatus()))
                .collect(Collectors.toList());
    }

    @Override
    public List<SyncRecord> getSyncsByStatus(String status) {
        return syncRecords.values().stream()
                .filter(r -> status.equals(r.getStatus()))
                .sorted(Comparator.comparing(SyncRecord::getStartTime).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getTaskStatus(String taskId) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("taskId", taskId);

        String currentSyncId = taskCurrentSyncId.get(taskId);
        List<SyncRecord> records = getSyncRecordsByTask(taskId);

        if (currentSyncId != null) {
            SyncRecord current = syncRecords.get(currentSyncId);
            if (current != null) {
                status.put("currentStatus", current.getStatus());
                status.put("currentSyncId", currentSyncId);
                status.put("sourceRecords", current.getSourceRecords());
                status.put("syncedRecords", current.getSyncedRecords());
                status.put("conflictCount", current.getConflictCount());
                status.put("retryCount", current.getRetryCount());
                status.put("startTime", current.getStartTime());
            }
        }

        if (!records.isEmpty()) {
            SyncRecord latest = records.get(0);
            status.put("lastSyncTime", latest.getEndTime());
            status.put("lastSyncStatus", latest.getStatus());
            status.put("lastSyncId", latest.getSyncId());
        }

        status.put("totalSyncs", records.size());
        status.put("failedSyncs", (int) records.stream()
                .filter(r -> Constants.SYNC_STATUS_FAILED.equals(r.getStatus())).count());
        status.put("completedSyncs", (int) records.stream()
                .filter(r -> Constants.SYNC_STATUS_COMPLETED.equals(r.getStatus())).count());

        if (!records.isEmpty()) {
            int totalSynced = records.stream().mapToInt(SyncRecord::getSyncedRecords).sum();
            int totalSource = records.stream().mapToInt(SyncRecord::getSourceRecords).sum();
            int totalConflicts = records.stream().mapToInt(SyncRecord::getConflictCount).sum();

            status.put("totalSyncedRecords", totalSynced);
            status.put("totalSourceRecords", totalSource);
            status.put("totalConflicts", totalConflicts);
            if (totalSource > 0) {
                status.put("syncSuccessRate", String.format("%.2f%%",
                        (double) totalSynced / totalSource * 100));
            }
        }

        return status;
    }

    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();

        List<SyncRecord> allRecords = new ArrayList<>(syncRecords.values());

        stats.put("totalSyncs", allRecords.size());
        stats.put("runningSyncs", getRunningSyncCount());
        stats.put("completedSyncs", getCompletedSyncCount());
        stats.put("failedSyncs", getFailedSyncCount());
        stats.put("retryingSyncs", (int) syncRecords.values().stream()
                .filter(r -> Constants.SYNC_STATUS_RETRYING.equals(r.getStatus())).count());

        int totalSynced = allRecords.stream().mapToInt(SyncRecord::getSyncedRecords).sum();
        int totalSource = allRecords.stream().mapToInt(SyncRecord::getSourceRecords).sum();
        int totalConflicts = allRecords.stream().mapToInt(SyncRecord::getConflictCount).sum();
        int totalRetries = allRecords.stream().mapToInt(SyncRecord::getRetryCount).sum();

        stats.put("totalSyncedRecords", totalSynced);
        stats.put("totalSourceRecords", totalSource);
        stats.put("totalConflicts", totalConflicts);
        stats.put("totalRetries", totalRetries);

        if (totalSource > 0) {
            stats.put("overallSuccessRate", String.format("%.2f%%",
                    (double) totalSynced / totalSource * 100));
        }

        Set<String> uniqueTasks = allRecords.stream()
                .map(SyncRecord::getTaskId)
                .collect(Collectors.toSet());
        stats.put("activeTasks", uniqueTasks.size());

        return stats;
    }

    @Override
    public void recordSync(SyncRecord record) {
        if (record.getSyncId() == null) {
            record.setSyncId("sync_" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (record.getStartTime() == null) {
            record.start();
        }
        syncRecords.put(record.getSyncId(), record);
        taskCurrentSyncId.put(record.getTaskId(), record.getSyncId());
        saveToRedis(record);
        logger.info("Started sync: {} for task: {}", record.getSyncId(), record.getTaskId());
    }

    @Override
    public void markComplete(String syncId) {
        SyncRecord record = syncRecords.get(syncId);
        if (record != null) {
            record.complete();
            saveToRedis(record);
            logger.info("Sync completed: {} (synced: {}, conflicts: {})",
                    syncId, record.getSyncedRecords(), record.getConflictCount());
        }
    }

    @Override
    public void markFailed(String syncId, String error) {
        SyncRecord record = syncRecords.get(syncId);
        if (record != null) {
            record.fail(error);
            saveToRedis(record);
            logger.error("Sync failed: {} - {}", syncId, error);
        }
    }

    @Override
    public void incrementRetry(String syncId) {
        SyncRecord record = syncRecords.get(syncId);
        if (record != null) {
            record.setRetryCount(record.getRetryCount() + 1);
            record.setStatus(Constants.SYNC_STATUS_RETRYING);
            saveToRedis(record);
            logger.info("Retry started for sync: {}, attempt: {}", syncId, record.getRetryCount());
        }
    }

    @Override
    public int getRunningSyncCount() {
        return (int) syncRecords.values().stream()
                .filter(r -> Constants.SYNC_STATUS_RUNNING.equals(r.getStatus()))
                .count();
    }

    @Override
    public int getFailedSyncCount() {
        return (int) syncRecords.values().stream()
                .filter(r -> Constants.SYNC_STATUS_FAILED.equals(r.getStatus()))
                .count();
    }

    @Override
    public int getCompletedSyncCount() {
        return (int) syncRecords.values().stream()
                .filter(r -> Constants.SYNC_STATUS_COMPLETED.equals(r.getStatus()))
                .count();
    }

    @Override
    public int getTotalSyncCount() {
        return syncRecords.size();
    }

    private void saveToRedis(SyncRecord record) {
        try {
            String json = objectMapper.writeValueAsString(record);
            redisTemplate.opsForValue().set(Constants.REDIS_KEY_PREFIX_SYNC_RECORD + record.getSyncId(), json);
            logger.debug("Persisted sync record: {}", record.getSyncId());
        } catch (Exception e) {
            logger.warn("Failed to save sync record to Redis: {}", record.getSyncId(), e);
        }
    }
}
