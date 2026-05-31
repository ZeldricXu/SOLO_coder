package com.taskflow.core.task.domain;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 任务定义 - 领域模型
 */
@Data
@Builder
public class Task {
    private String taskId;
    private String tenantId;
    private String name;
    private String description;
    private String type;
    private String status;
    private String cronExpression;
    private LocalDateTime nextRunTime;
    private LocalDateTime lastRunTime;
    private Map<String, Object> parameters;
    private String handlerType;
    private Integer timeoutSeconds;
    private Integer maxRetry;
    private String flowId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
