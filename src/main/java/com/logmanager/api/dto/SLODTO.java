package com.logmanager.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.Duration;
import java.util.Map;

@Data
public class SLODTO {
    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "serviceName is required")
    private String serviceName;

    @NotNull(message = "targetPercentage is required")
    private Double targetPercentage;

    @NotNull(message = "window is required")
    private Duration window;

    private Map<String, String> sliConfig;

    private String description;
}
