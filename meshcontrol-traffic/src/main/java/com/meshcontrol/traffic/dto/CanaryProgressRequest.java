package com.meshcontrol.traffic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class CanaryProgressRequest {

    @NotBlank(message = "releaseId is required")
    private String releaseId;

    @NotNull(message = "trafficSplit is required")
    private Map<String, Object> trafficSplit;
}
