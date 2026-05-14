package com.datasync.service.monitor;

import com.datasync.model.SyncRecord;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface StatusMonitor {

    void updateStatus(String taskId, String syncId, String status);

    void updateProgress(String syncId, int sourceRecords, int syncedRecords, int conflictCount);

    Optional<SyncRecord> getSyncRecord(String syncId);

    List<SyncRecord> getSyncRecordsByTask(String taskId);

    List<SyncRecord> getSyncRecordsByTask(String taskId, int limit);

    List<SyncRecord> getSyncRecordsByTimeRange(Instant startTime, Instant endTime);

    List<SyncRecord> getSyncRecordsByTimeRange(String taskId, Instant startTime, Instant endTime);

    List<SyncRecord> getRunningSyncs();

    List<SyncRecord> getFailedSyncs();

    List<SyncRecord> getCompletedSyncs();

    List<SyncRecord> getSyncsByStatus(String status);

    Map<String, Object> getTaskStatus(String taskId);

    Map<String, Object> getStatistics();

    void recordSync(SyncRecord record);

    void markComplete(String syncId);

    void markFailed(String syncId, String error);

    void incrementRetry(String syncId);

    int getRunningSyncCount();

    int getFailedSyncCount();

    int getCompletedSyncCount();

    int getTotalSyncCount();

    void loadAllRecordsFromPersistence();
}
