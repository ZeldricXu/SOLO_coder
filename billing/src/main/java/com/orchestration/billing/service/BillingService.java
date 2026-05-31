package com.orchestration.billing.service;

import com.orchestration.persistence.entity.BillingCycle;
import com.orchestration.persistence.entity.BillingItem;
import com.orchestration.persistence.entity.PricingRule;
import com.orchestration.persistence.entity.UsageRecord;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface BillingService {

    Long recordUsage(Long tenantId, String resourceType, Long usageAmount, String unit, Map<String, String> tags);

    List<UsageRecord> listUsageRecords(Long tenantId, String resourceType, Long startTime, Long endTime);

    Long createPricingRule(PricingRule rule);

    boolean updatePricingRule(PricingRule rule);

    List<PricingRule> listPricingRules();

    PricingRule getPricingRule(Long id);

    boolean deletePricingRule(Long id);

    Long generateBillingCycle(Long tenantId, String cycleType);

    BillingCycle getBillingCycle(Long id);

    List<BillingCycle> listBillingCycles(Long tenantId, String status);

    List<BillingItem> listBillingItems(Long cycleId);

    BigDecimal calculateUsageCost(Long tenantId, String resourceType, Long startTime, Long endTime);

    Map<String, Object> getTenantBillingSummary(Long tenantId, String cycleCode);

    boolean processPayment(Long cycleId, BigDecimal amount);

    void generateMonthlyBills();

    Map<String, Object> getPricingEstimate(Long tenantId, Map<String, Long> estimatedUsage);
}
