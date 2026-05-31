package com.tracetopology.api.service;

import com.tracetopology.domain.schedule.TaskExecution;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface ScheduleService {

    TaskExecution executeTask(String taskType, Map<String, Object> params, int timeoutSeconds);

    TaskExecution scheduleTask(String taskId, String taskType, String cronExpression, Map<String, Object> params);

    String scheduleTask(String taskType, Map<String, Object> params, Instant runAt);

    String scheduleRecurringTask(String taskType, Map<String, Object> params, Duration interval);

    String scheduleCronTask(String taskType, Map<String, Object> params, String cronExpression);

    boolean cancelTask(String taskId);

    TaskExecution getTaskExecution(String executionId);

    List<TaskExecution> listTaskExecutions(String taskType, int pageNum, int pageSize);

    List<TaskExecution> getRunningTasks();

    int recoverFailedTasks();

    Map<String, Object> getTaskStatus(String taskId);

    List<Map<String, Object>> listTasks(String status, int pageNum, int pageSize);

    void trackTaskProgress(String taskId, double progress, String message);

    void completeTask(String taskId, Map<String, Object> result);

    void failTask(String taskId, String error);
}
