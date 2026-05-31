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
public class ErrorBudgetResponse {

    private String sloId;

    private String sloName;

    private String serviceName;

    private Double totalBudget;

    private Double consumedBudget;

    private Double remainingBudget;

    private Double remainingBudgetPercent;

    private Double burnRate;

    private Double sloTarget;

    private Double currentSliValue;

    private Instant windowStart;

    private Instant windowEnd;

    private Duration timeWindow;

    private String budgetStatus;

    private Instant estimatedExhaustionTime;

    private List<BurnRateTrend> burnRateTrend;

    private Map<String, Object> metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BurnRateTrend {
        private Instant timestamp;
        private Double burnRate;
        private Double remainingBudget;
    }
}
