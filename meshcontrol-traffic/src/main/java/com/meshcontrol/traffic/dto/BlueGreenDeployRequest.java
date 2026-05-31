package com.meshcontrol.traffic.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BlueGreenDeployRequest {

    @NotBlank(message = "serviceName is required")
    private String serviceName;

    @NotBlank(message = "namespace is required")
    private String namespace;

    @NotBlank(message = "blueVersion is required")
    private String blueVersion;

    @NotBlank(message = "greenVersion is required")
    private String greenVersion;

    private Integer rolloutTimeoutMs = 300000;
    private Boolean autoRollback = true;
}
