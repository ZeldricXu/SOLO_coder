package com.logmanager.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
public class LogEntryDTO {
    private String traceId;

    @NotBlank(message = "serviceName is required")
    private String serviceName;

    @NotBlank(message = "level is required")
    private String level;

    @NotBlank(message = "message is required")
    private String message;

    private String loggerName;

    private String threadName;

    private Instant timestamp;

    private Map<String, String> tags;

    private Map<String, Object> metadata;
}
