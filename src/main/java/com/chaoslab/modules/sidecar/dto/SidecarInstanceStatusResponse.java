package com.chaoslab.modules.sidecar.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class SidecarInstanceStatusResponse {

    private String instanceId;
    private String policyId;
    private String targetPod;
    private String namespace;
    private String status;
    private String configHash;
    private Map<String, Object> resources;
    private LocalDateTime lastHeartbeat;
    private LocalDateTime createdAt;
}
