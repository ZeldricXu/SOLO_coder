package com.meshcontrol.traffic.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TrafficPolicyRequest {

    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "type is required")
    private String type;

    private String namespace;
    private String serviceName;
    private List<Map<String, Object>> matchRules;
    private List<Map<String, Object>> routes;
    private Map<String, Object> mirrorConfig;
    private Map<String, Object> circuitBreaker;
    private Map<String, Object> retryPolicy;
    private Integer timeoutMs;
    private Boolean enabled = true;
    private Integer priority = 0;
}
