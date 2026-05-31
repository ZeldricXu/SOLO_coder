package com.taskflow.flow.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class FlowDefinition {
    private String flowId;
    private String tenantId;
    private String name;
    private String description;
    private Integer version;
    private List<FlowNode> nodes;
    private List<FlowEdge> edges;
    private String status;
    private Map<String, Object> variables;
    private Map<String, Object> config;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
}
