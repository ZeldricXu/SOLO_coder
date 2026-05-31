package com.taskflow.flow.model;

import lombok.Data;

import java.util.Map;

@Data
public class FlowEdge {
    private String edgeId;
    private String sourceNodeId;
    private String targetNodeId;
    private String sourcePort;
    private String targetPort;
    private Map<String, Object> style;
    private String label;
    private Map<String, Object> condition;
}
