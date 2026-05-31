package com.taskflow.billing.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class UsageSummary {
    private String tenantId;
    private String period;
    private Map<String, BigDecimal> usageByResource;
    private BigDecimal estimatedCost;
    private BigDecimal totalFreeQuota;
    private Map<String, BigDecimal> usagePercentage;
}
