package com.meshcontrol.fault.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class FaultInjectRequest {

    @NotBlank(message = "scenarioId is required")
    private String scenarioId;

    private List<String> targets;
    private Integer durationSeconds;
}
