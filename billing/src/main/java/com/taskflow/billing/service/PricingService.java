package com.taskflow.billing.service;

import com.taskflow.billing.model.PricingRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class PricingService {

    private final Map<String, PricingRule> pricingRules = new ConcurrentHashMap<>();

    public PricingService() {
        initDefaultRules();
    }

    private void initDefaultRules() {
        registerRule(PricingRule.builder()
                .ruleId("task_executions")
                .resourceType("task_executions")
                .unitPrice(new BigDecimal("0.01"))
                .currency("CNY")
                .billingModel("per_unit")
                .freeQuota(new BigDecimal("1000"))
                .active(true)
                .build());

        registerRule(PricingRule.builder()
                .ruleId("compute_minutes")
                .resourceType("compute_minutes")
                .unitPrice(new BigDecimal("0.05"))
                .currency("CNY")
                .billingModel("per_unit")
                .freeQuota(new BigDecimal("10000"))
                .active(true)
                .build());

        registerRule(PricingRule.builder()
                .ruleId("api_calls")
                .resourceType("api_calls")
                .unitPrice(new BigDecimal("0.001"))
                .currency("CNY")
                .billingModel("per_unit")
                .freeQuota(new BigDecimal("100000"))
                .active(true)
                .build());

        registerRule(PricingRule.builder()
                .ruleId("storage_gb")
                .resourceType("storage_gb")
                .unitPrice(new BigDecimal("2.00"))
                .currency("CNY")
                .billingModel("monthly")
                .freeQuota(new BigDecimal("10"))
                .active(true)
                .build());
    }

    public void registerRule(PricingRule rule) {
        pricingRules.put(rule.getResourceType(), rule);
        log.info("Pricing rule registered: {}", rule.getResourceType());
    }

    public PricingRule getRule(String resourceType) {
        return pricingRules.get(resourceType);
    }

    public BigDecimal calculateCost(String resourceType, BigDecimal usage) {
        PricingRule rule = getRule(resourceType);
        if (rule == null || !rule.isActive()) {
            return BigDecimal.ZERO;
        }

        BigDecimal billableUsage = usage.subtract(rule.getFreeQuota());
        if (billableUsage.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return billableUsage.multiply(rule.getUnitPrice());
    }

    public Map<String, PricingRule> getAllRules() {
        return pricingRules;
    }
}
