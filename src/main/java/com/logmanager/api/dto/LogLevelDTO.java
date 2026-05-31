package com.logmanager.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.Duration;

@Data
public class LogLevelDTO {
    @NotBlank(message = "serviceName is required")
    private String serviceName;

    @NotBlank(message = "loggerName is required")
    private String loggerName;

    @NotBlank(message = "level is required")
    private String level;

    private Duration ttl;

    private String reason;

    private String operator;
}
