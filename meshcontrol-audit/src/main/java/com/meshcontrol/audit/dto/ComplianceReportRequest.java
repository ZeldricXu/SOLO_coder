package com.meshcontrol.audit.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ComplianceReportRequest {

    @NotNull(message = "startTime is required")
    private LocalDateTime startTime;

    @NotNull(message = "endTime is required")
    private LocalDateTime endTime;

    private List<String> resourceTypes;
    private List<String> operators;
    private String reportFormat = "json";
}
