package com.metricplatform.service;

import com.metricplatform.dto.TaskExecutionResult;
import com.metricplatform.entity.SysScheduledTask;

import java.util.Map;

public interface TaskHandler {

    String getTaskType();

    TaskExecutionResult execute(SysScheduledTask task, Map<String, Object> context);

    default boolean supports(String taskType) {
        return getTaskType().equalsIgnoreCase(taskType);
    }
}
