package com.datasync.service.retry;

import com.datasync.model.RetryRecord;
import com.datasync.model.SyncRecord;
import com.datasync.model.SyncTaskConfig;

import java.util.List;
import java.util.Optional;

public interface RetryScheduler {

    RetryRecord scheduleRetry(SyncRecord failedRecord, SyncTaskConfig task);

    RetryRecord scheduleRetry(String syncId, String taskId, int maxRetries);

    Optional<SyncRecord> retrySync(String syncId);

    Optional<SyncRecord> retrySync(RetryRecord retryRecord);

    void cancelRetry(String syncId);

    boolean hasPendingRetry(String taskId);

    int getPendingRetryCount(String taskId);

    void clearRetries(String taskId);

    Optional<RetryRecord> getRetryRecord(String syncId);

    List<RetryRecord> getRetryRecordsByTask(String taskId);

    List<RetryRecord> getAllRetryRecords();

    List<RetryRecord> getPendingRetryRecords();

    List<RetryRecord> getCompletedRetryRecords();

    List<RetryRecord> getExhaustedRetryRecords();

    void loadAllRetriesFromPersistence();

    int getTotalRetriesCount();

    int getSuccessfulRetriesCount();

    int getExhaustedRetriesCount();
}
