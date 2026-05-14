package com.taskscheduler;

import com.taskscheduler.entity.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    public static TaskConfig createTaskConfig() {
        return createTaskConfig("default-task");
    }

    public static TaskConfig createTaskConfig(String taskId) {
        TaskConfig task = new TaskConfig();
        task.setTaskId(taskId);
        task.setTaskName("测试任务 - " + taskId);
        task.setTaskType("batch");
        task.setExecuteCommand("echo 'test'");
        task.setCronExpression("0 * * * * ?");
        task.setTimezone("Asia/Shanghai");
        task.setRetryCount(3);
        task.setTimeoutSeconds(300);
        task.setPriority(1);
        task.setEnabled(true);
        task.setMaxConcurrent(5);
        task.setDependencies(new ArrayList<>());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }

    public static TaskConfig createTaskConfigWithDependencies(String taskId, List<String> dependencies) {
        TaskConfig task = createTaskConfig(taskId);
        task.setDependencies(dependencies);
        return task;
    }

    public static TaskConfig createHighPriorityTask(String taskId) {
        TaskConfig task = createTaskConfig(taskId);
        task.setPriority(10);
        return task;
    }

    public static TaskConfig createTaskWithRetry(String taskId, int retryCount) {
        TaskConfig task = createTaskConfig(taskId);
        task.setRetryCount(retryCount);
        return task;
    }

    public static Executor createExecutor(String executorId) {
        return createExecutor(executorId, 0, 10);
    }

    public static Executor createExecutor(String executorId, int currentLoad, int maxCapacity) {
        Executor executor = new Executor();
        executor.setExecutorId(executorId);
        executor.setExecutorName("执行器 - " + executorId);
        executor.setExecutorAddress("192.168.1." + (100 + Math.abs(executorId.hashCode()) % 100) + ":9000");
        executor.setExecutorStatus("online");
        executor.setCurrentLoad(currentLoad);
        executor.setMaxCapacity(maxCapacity);
        executor.setTaskType("batch");
        executor.setRegisteredAt(LocalDateTime.now());
        executor.setLastActive(LocalDateTime.now());
        executor.setCreatedAt(LocalDateTime.now());
        executor.setUpdatedAt(LocalDateTime.now());
        return executor;
    }

    public static Executor createOfflineExecutor(String executorId) {
        Executor executor = createExecutor(executorId);
        executor.setExecutorStatus("offline");
        return executor;
    }

    public static Executor createFullExecutor(String executorId) {
        Executor executor = createExecutor(executorId);
        executor.setCurrentLoad(executor.getMaxCapacity());
        return executor;
    }

    public static List<Executor> createExecutors(int count) {
        List<Executor> executors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            executors.add(createExecutor("executor_" + i));
        }
        return executors;
    }

    public static List<Executor> createExecutorsWithMixedLoad(int count) {
        List<Executor> executors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            executors.add(createExecutor("executor_" + i, i, 10));
        }
        return executors;
    }

    public static ExecuteRecord createExecuteRecord(String executeId, String taskId) {
        return createExecuteRecord(executeId, taskId, "success");
    }

    public static ExecuteRecord createExecuteRecord(String executeId, String taskId, String status) {
        ExecuteRecord record = new ExecuteRecord();
        record.setExecuteId(executeId);
        record.setTaskId(taskId);
        record.setExecuteTime(LocalDateTime.now());
        record.setExecutorId("executor_0");
        record.setExecuteStatus(status);
        record.setExecuteDurationSeconds(10L);
        record.setExecuteResult("执行完成");
        record.setStartTime(LocalDateTime.now().minusSeconds(10));
        record.setEndTime(LocalDateTime.now());
        record.setTriggerType("scheduled");
        record.setRetryNumber(0);
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    public static ExecuteRecord createRunningExecuteRecord(String executeId, String taskId) {
        ExecuteRecord record = createExecuteRecord(executeId, taskId, "running");
        record.setEndTime(null);
        record.setExecuteDurationSeconds(null);
        record.setExecuteResult(null);
        return record;
    }

    public static ExecuteRecord createFailedExecuteRecord(String executeId, String taskId) {
        ExecuteRecord record = createExecuteRecord(executeId, taskId, "failed");
        record.setExecuteResult("执行失败: 数据库连接超时");
        return record;
    }

    public static FailRecord createFailRecord(String taskId, String executeId) {
        return createFailRecord(taskId, executeId, 0, "retrying");
    }

    public static FailRecord createFailRecord(String taskId, String executeId, int retryCount, String status) {
        FailRecord failRecord = new FailRecord();
        failRecord.setTaskId(taskId);
        failRecord.setExecuteId(executeId);
        failRecord.setFailReason("数据库连接超时");
        failRecord.setRetryCount(retryCount);
        failRecord.setStatus(status);
        failRecord.setNextRetryTime(LocalDateTime.now().plusSeconds(30));
        failRecord.setCreatedAt(LocalDateTime.now());
        failRecord.setUpdatedAt(LocalDateTime.now());
        return failRecord;
    }

    public static FailRecord createPendingRetryRecord(String taskId, String executeId) {
        FailRecord record = createFailRecord(taskId, executeId, 1, "retrying");
        record.setNextRetryTime(LocalDateTime.now().minusSeconds(10));
        return record;
    }

    public static Dependency createDependency(String taskId, String dependsOn) {
        Dependency dependency = new Dependency();
        dependency.setTaskId(taskId);
        dependency.setDependsOn(dependsOn);
        dependency.setDependencyType("sequential");
        dependency.setCreatedAt(LocalDateTime.now());
        return dependency;
    }

    public static List<Dependency> createDependencyChain(String... taskIds) {
        List<Dependency> dependencies = new ArrayList<>();
        for (int i = 1; i < taskIds.length; i++) {
            dependencies.add(createDependency(taskIds[i], taskIds[i - 1]));
        }
        return dependencies;
    }

    public static TaskLog createTaskLog(String executeId, String taskId) {
        return createTaskLog(executeId, taskId, "info", "任务开始执行");
    }

    public static TaskLog createTaskLog(String executeId, String taskId, String level, String content) {
        TaskLog log = new TaskLog();
        log.setExecuteId(executeId);
        log.setTaskId(taskId);
        log.setLogLevel(level);
        log.setLogContent(content);
        log.setLogTime(LocalDateTime.now());
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }

    public static String generateExecuteId() {
        return "exec_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
    }

    public static String generateTaskId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String generateExecutorId() {
        return "executor_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
