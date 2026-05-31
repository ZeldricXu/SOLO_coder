package com.taskflow.core.task.domain;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 任务执行请求 - 领域模型
 */
@Data
@Builder
public class TaskRequest {
    private String taskId;
    private String tenantId;
    private String namespace;
    private Map<String, Object> params;
    private Map<String, Object> payload;
    private String triggerType;
    private String traceId;
    private String userId;
}
