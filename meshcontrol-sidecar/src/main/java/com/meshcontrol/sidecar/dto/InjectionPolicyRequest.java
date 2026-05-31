package com.meshcontrol.sidecar.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class InjectionPolicyRequest {

    @NotBlank(message = "name is required")
    private String name;

    private String namespace;
    private Map<String, Object> selector;
    private Map<String, Object> sidecarTemplate;
    private Map<String, Object> resources;
    private Boolean enabled = true;
    private Integer priority = 0;
}
