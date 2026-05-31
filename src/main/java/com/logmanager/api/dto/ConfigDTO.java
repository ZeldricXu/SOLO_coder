package com.logmanager.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.Map;

@Data
public class ConfigDTO {
    @NotBlank(message = "namespace is required")
    private String namespace;

    @NotBlank(message = "configId is required")
    private String configId;

    @NotNull(message = "parameters is required")
    private Map<String, Object> parameters;

    private String source;
}
