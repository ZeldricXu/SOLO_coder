package com.taskflow.flow.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class FlowInstance {
    private String instanceId;
    private String flowId;
    private Integer flowVersion;
    private String tenantId;
    private String status;
    private String currentNodeId;
    private Map<String, Object> variables;
    private List<FlowNodeExecution> executionHistory;
    private Map<String, Object> result;
    private String errorDetail;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
