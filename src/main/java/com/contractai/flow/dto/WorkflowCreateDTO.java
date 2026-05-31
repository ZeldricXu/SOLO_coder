package com.contractai.flow.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class WorkflowCreateDTO {
    private String flowCode;
    private String flowName;
    private String category;
    private String description;
    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;
    private Map<String, Object> variables;
    private Map<String, Object> formSchema;
}

@Data
class WorkflowUpdateDTO {
    private String flowName;
    private String category;
    private String description;
    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;
    private Map<String, Object> variables;
    private Map<String, Object> formSchema;
}

@Data
class FlowValidationResult {
    private boolean valid;
    private List<String> errors;
    private List<String> warnings;
}

@Data
class InstanceStartDTO {
    private Long flowId;
    private String businessKey;
    private Map<String, Object> variables;
    private Map<String, Object> formData;
    private Long startedBy;
}

@Data
class NodeConfigDTO {
    private String nodeId;
    private String nodeName;
    private String nodeType;
    private Integer positionX;
    private Integer positionY;
    private Map<String, Object> config;
    private Map<String, Object> formSchema;
}

@Data
class EdgeConfigDTO {
    private String edgeId;
    private String sourceNodeId;
    private String targetNodeId;
    private String edgeName;
    private String conditionExpression;
    private Integer priority;
}
