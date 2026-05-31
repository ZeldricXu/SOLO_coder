package com.metricplatform.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LifecycleExecutionResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tableName;
    private String operation;
    private long affectedRows;
    private boolean success;
    private String errorMessage;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private long durationMs;
}
