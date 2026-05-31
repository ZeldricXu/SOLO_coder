package com.chaoslab.modules.dns.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class AsyncDnsTaskResponse {
    private String taskId;
    private String requestId;
    private String domain;
    private String queryType;
    private String status;
    private String priority;
    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;
    private Map<String, Object> result;
    private String errorMessage;
    private Integer retryCount;
    private Map<String, Object> context;
}
