package com.meshcontrol.sidecar.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class ResourceLimitUpdateRequest {

    @NotBlank(message = "sidecarId is required")
    private String sidecarId;

    private Map<String, Object> resources;
}
