package com.datapipeline.scheduler;

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
    private String taskName;
    private ScheduledTask.Status status;
    private Instant startedAt;
    private Instant completedAt;
    private long durationMs;
    private String lastError;
    private Map<String, Object> metadata;

}
