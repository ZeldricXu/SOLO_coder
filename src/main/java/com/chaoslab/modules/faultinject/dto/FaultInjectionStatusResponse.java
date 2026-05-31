package com.chaoslab.modules.faultinject.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class FaultInjectionStatusResponse {

    private String runId;
    private String scenarioId;
    private String scenarioName;
    private String faultType;
    private String status;
    private String phase;
    private List<String> targets;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Boolean rollbackTriggered;
    private String rollbackReason;
    private LocalDateTime rollbackCompletedAt;
    private Map<String, Object> metrics;
    private Map<String, Object> faultConfig;
}
