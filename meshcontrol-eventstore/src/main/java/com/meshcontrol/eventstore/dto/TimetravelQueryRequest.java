package com.meshcontrol.eventstore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TimetravelQueryRequest {

    @NotBlank(message = "aggregateId is required")
    private String aggregateId;

    @NotBlank(message = "aggregateType is required")
    private String aggregateType;

    @NotNull(message = "timestamp is required")
    private LocalDateTime timestamp;
}
