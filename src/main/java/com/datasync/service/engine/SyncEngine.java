package com.datasync.service.engine;

import com.datasync.dto.SyncExecuteRequest;
import com.datasync.model.SyncRecord;

public interface SyncEngine {

    SyncRecord executeSync(SyncExecuteRequest request);

    SyncRecord executeSync(String taskId);

    void executeScheduledSync(String taskId);

    void pauseTask(String taskId);

    void resumeTask(String taskId);

    boolean isTaskRunning(String taskId);
}
