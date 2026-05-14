package com.datasync.service.retry;

import com.datasync.model.RetryFailureDetail;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface RetryFailureManager {

    RetryFailureDetail recordFailure(RetryFailureDetail detail);

    RetryFailureDetail recordFailure(
            String taskId, String syncId, String retryId,
            int attempt, Throwable exception,
            String dataKey, Map<String, Object> dataSnapshot
    );

    Optional<RetryFailureDetail> getFailureDetail(String detailId);

    List<RetryFailureDetail> getFailuresByTask(String taskId);

    List<RetryFailureDetail> getFailuresBySync(String syncId);

    List<RetryFailureDetail> getFailuresByRetry(String retryId);

    List<RetryFailureDetail> getFailuresByType(String failureType);

    List<RetryFailureDetail> getUnresolvedFailures();

    List<RetryFailureDetail> getFailuresByTimeRange(Instant startTime, Instant endTime);

    List<RetryFailureDetail> getAllFailures();

    RetryFailureDetail markResolved(String detailId, String notes);

    RetryFailureDetail markIgnored(String detailId, String notes);

    int getFailureCountByTask(String taskId);

    int getFailureCountByType(String failureType);

    int getUnresolvedFailureCount();

    Map<String, Integer> getFailureTypeStatistics(String taskId);

    Map<String, Object> getFailureStatistics();

    void loadAllFailuresFromPersistence();
}
