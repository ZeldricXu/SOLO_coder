package com.taskflow.flow.model;

import lombok.Data;

import java.util.Map;

@Data
public class FlowNode {
    private String nodeId;
    private String nodeType;
    private String name;
    private String description;
    private Map<String, Object> position;
    private Map<String, Object> config;
    private Map<String, Object> data;
    private String status;
    private Integer sortOrder;
}
