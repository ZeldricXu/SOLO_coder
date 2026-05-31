package com.meshcontrol.audit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class CommandRecordRequest {

    @NotBlank(message = "commandType is required")
    private String commandType;

    private String aggregateId;
    private String aggregateType;

    @NotNull(message = "payload is required")
    private Map<String, Object> payload;

    private Map<String, Object> metadata;
    private String executedBy;
}
