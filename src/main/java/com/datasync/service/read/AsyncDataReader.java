package com.datasync.service.read;

import com.datasync.model.DataReadTask;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public interface AsyncDataReader {

    DataReadTask submitReadTask(
            String taskId,
            String syncId,
            String dataSourceId,
            String tableName,
            String filterCondition,
            String dataKeyField
    );

    DataReadTask submitReadTask(
            String taskId,
            String syncId,
            String dataSourceId,
            String tableName,
            String filterCondition,
            String dataKeyField,
            Integer batchSize,
            Integer priority
    );

    DataReadTask submitReadTask(DataReadTask task);

    Optional<DataReadTask> getReadTask(String readTaskId);

    List<DataReadTask> getReadTasksBySync(String syncId);

    List<DataReadTask> getReadTasksByTask(String taskId);

    List<DataReadTask> getPendingReadTasks();

    List<DataReadTask> getRunningReadTasks();

    List<DataReadTask> getCompletedReadTasks();

    List<Map<String, Object>> waitForData(String readTaskId, long timeoutMs) throws InterruptedException;

    void cancelReadTask(String readTaskId);

    void registerCallback(String readTaskId, Consumer<DataReadTask> callback);

    Map<String, Object> getReadTaskStatistics();

    void loadAllTasksFromPersistence();

    int getQueueSize();

    int getActiveWorkerCount();
}
