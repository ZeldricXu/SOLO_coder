package com.taskflow.billing.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class PricingRule {
    private String ruleId;
    private String resourceType;
    private BigDecimal unitPrice;
    private String currency;
    private String billingModel;
    private Map<String, Object> tiers;
    private BigDecimal freeQuota;
    private boolean active;
}
