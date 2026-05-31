package com.meshcontrol.traffic.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class CanaryReleaseRequest {

    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "serviceName is required")
    private String serviceName;

    @NotBlank(message = "namespace is required")
    private String namespace;

    @NotBlank(message = "primaryVersion is required")
    private String primaryVersion;

    @NotBlank(message = "canaryVersion is required")
    private String canaryVersion;

    private Map<String, Object> trafficSplit;
    private String strategy = "percentage";
}
