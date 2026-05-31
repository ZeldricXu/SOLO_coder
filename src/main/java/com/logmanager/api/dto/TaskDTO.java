package com.logmanager.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
public class TaskDTO {
    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "type is required")
    private String type;

    @NotNull(message = "parameters is required")
    private Map<String, Object> parameters;

    private String scheduledBy;

    private Instant scheduledAt;
}
