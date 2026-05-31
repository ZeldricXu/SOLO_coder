package com.taskflow.core.task.domain;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 任务执行结果 - 领域模型
 */
@Data
@Builder
public class TaskResult {
    private String runId;
    private String taskId;
    private String status;
    private String phase;
    private Double progress;
    private Object data;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;
    private Map<String, Object> metrics;
}
