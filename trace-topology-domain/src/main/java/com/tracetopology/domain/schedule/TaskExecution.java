package com.tracetopology.domain.schedule;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskExecution {

    private String executionId;
    private String taskId;
    private String taskType;
    private String phase;
    private double progress;
    private Instant startedAt;
    private Instant completedAt;
    private String errorDetail;
    private Map<String, Object> params;
    private Map<String, Object> result;
    private int timeoutSeconds;

    public boolean isRunning() {
        return "running".equals(phase) || "executing".equals(phase);
    }

    public boolean isCompleted() {
        return "completed".equals(phase);
    }

    public boolean isFailed() {
        return "failed".equals(phase);
    }

    public boolean isCancelled() {
        return "cancelled".equals(phase);
    }
}
