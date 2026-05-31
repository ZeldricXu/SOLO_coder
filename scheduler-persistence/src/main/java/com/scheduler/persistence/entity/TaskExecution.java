package com.scheduler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.scheduler.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_executions")
public class TaskExecution extends BaseEntity {
    private String runId;
    private String taskId;
    private String phase;
    private Double progress;
    private Instant startedAt;
    private Instant completedAt;
    private String status;
    private String errorDetail;
    private String errorMessage;
    private String stackTrace;
    private Map<String, Object> result;
    private Integer retryCount;
    private String scheduledBy;
    private String executedBy;
    private Long durationMs;
    private Map<String, String> context;
}
