package com.solocoder.platform.scheduler.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskExecution implements Serializable {

    private static final long serialVersionUID = 1L;

    private String executionId;
    private String taskId;
    private ExecutionStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private long durationMs;
    private String result;
    private String errorMessage;
    private int retryCount;

    public enum ExecutionStatus {
        PENDING, RUNNING, COMPLETED, FAILED, RETRYING, CANCELLED
    }
}
