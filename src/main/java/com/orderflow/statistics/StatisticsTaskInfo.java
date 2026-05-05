package com.orderflow.statistics;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class StatisticsTaskInfo {
    private String taskId;
    private String taskType;
    private String status;
    private long submittedAt;
    private Integer limit;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
}
