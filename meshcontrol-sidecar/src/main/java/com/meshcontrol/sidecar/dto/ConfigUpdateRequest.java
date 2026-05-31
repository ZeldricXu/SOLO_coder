package com.meshcontrol.sidecar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class ConfigUpdateRequest {

    @NotBlank(message = "namespace is required")
    private String namespace;

    @NotNull(message = "parameters is required")
    private Map<String, Object> parameters;

    private Boolean enabled = true;
}
