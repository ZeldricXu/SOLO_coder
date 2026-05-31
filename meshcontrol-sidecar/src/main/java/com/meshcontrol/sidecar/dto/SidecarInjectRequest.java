package com.meshcontrol.sidecar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class SidecarInjectRequest {

    @NotBlank(message = "podName is required")
    @Size(max = 253, message = "podName exceeds maximum length of 253")
    private String podName;

    @NotBlank(message = "namespace is required")
    @Size(max = 63, message = "namespace exceeds maximum length of 63")
    private String namespace;

    @Size(max = 63, message = "nodeName exceeds maximum length of 63")
    private String nodeName;

    @NotBlank(message = "serviceName is required")
    @Size(max = 63, message = "serviceName exceeds maximum length of 63")
    private String serviceName;

    @NotBlank(message = "version is required")
    @Size(max = 32, message = "version exceeds maximum length of 32")
    private String version;

    @Size(max = 2000, message = "resources exceeds maximum size of 2KB")
    private Map<String, Object> resources = new HashMap<>();

    @Size(max = 64, message = "injectionMode exceeds maximum length of 64")
    private String injectionMode = "auto";

    @NotNull(message = "configVersion is required")
    private Integer configVersion;
}
