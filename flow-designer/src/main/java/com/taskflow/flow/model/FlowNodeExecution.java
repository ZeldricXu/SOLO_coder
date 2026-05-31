package com.taskflow.flow.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class FlowNodeExecution {
    private String executionId;
    private String nodeId;
    private String nodeType;
    private String status;
    private Map<String, Object> input;
    private Map<String, Object> output;
    private String errorDetail;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
}
