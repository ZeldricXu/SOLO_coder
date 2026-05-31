package com.datastandard.modules.slo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BurnAlertResponse {

    private String alertId;

    private String sloId;

    private String sloName;

    private String serviceName;

    private String alertLevel;

    private Double burnRate;

    private Double threshold;

    private Double remainingBudget;

    private Double remainingBudgetPercent;

    private Duration windowDuration;

    private Instant windowStart;

    private Instant windowEnd;

    private Instant alertTime;

    private String alertStatus;

    private String severity;

    private List<String> notifications;

    private Map<String, Object> additionalInfo;

    private String description;
}
