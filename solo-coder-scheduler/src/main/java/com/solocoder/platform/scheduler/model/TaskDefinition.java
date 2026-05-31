package com.solocoder.platform.scheduler.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;
    private String taskName;
    private String taskType;
    private String cronExpression;
    private Duration fixedDelay;
    private Duration fixedRate;
    private Map<String, Object> parameters;
    private int maxRetries;
    private Duration retryInterval;
    private LocalDateTime createdAt;
}
