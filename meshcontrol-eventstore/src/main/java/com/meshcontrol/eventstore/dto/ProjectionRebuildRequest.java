package com.meshcontrol.eventstore.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProjectionRebuildRequest {

    @NotBlank(message = "aggregateId is required")
    private String aggregateId;

    @NotBlank(message = "aggregateType is required")
    private String aggregateType;

    private Integer fromVersion;
    private Integer toVersion;
}
