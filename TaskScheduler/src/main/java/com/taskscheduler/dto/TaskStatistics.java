package com.taskscheduler.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatistics {

    private String taskId;
    private long totalExecutions;
    private long successCount;
    private long failedCount;
    private long runningCount;
    private double averageExecutionTime;
    private double successRate;
}
