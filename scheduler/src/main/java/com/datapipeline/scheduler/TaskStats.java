package com.datapipeline.scheduler;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStats {

    public static final TaskStats EMPTY = TaskStats.builder()
            .totalExecutions(0)
            .successCount(0)
            .failureCount(0)
            .averageDurationMs(0)
            .maxDurationMs(0)
            .minDurationMs(0)
            .successRate(0.0)
            .build();

    private String taskId;
    private int totalExecutions;
    private int successCount;
    private int failureCount;
    private long averageDurationMs;
    private long maxDurationMs;
    private long minDurationMs;
    private double successRate;
    private Instant lastExecutionAt;

}
