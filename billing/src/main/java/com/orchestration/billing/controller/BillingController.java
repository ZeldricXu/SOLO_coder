package com.orchestration.billing.controller;

import com.orchestration.common.api.ApiConstants;
import com.orchestration.common.base.Result;
import com.orchestration.billing.service.BillingService;
import com.orchestration.persistence.entity.BillingCycle;
import com.orchestration.persistence.entity.BillingItem;
import com.orchestration.persistence.entity.PricingRule;
import com.orchestration.persistence.entity.UsageRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.API_V1_PREFIX + "/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;

    @PostMapping("/usage/record")
    public Result<Long> recordUsage(
            @RequestParam Long tenantId,
            @RequestParam String resourceType,
            @RequestParam Long usageAmount,
            @RequestParam(required = false) String unit,
            @RequestBody(required = false) Map<String, String> tags) {
        return Result.success(billingService.recordUsage(tenantId, resourceType, usageAmount, unit, tags));
    }

    @GetMapping("/usage/records")
    public Result<List<UsageRecord>> listUsageRecords(
            @RequestParam Long tenantId,
            @RequestParam(required = false) String resourceType,
            @RequestParam Long startTime,
            @RequestParam Long endTime) {
        return Result.success(billingService.listUsageRecords(tenantId, resourceType, startTime, endTime));
    }

    @PostMapping("/pricing-rules")
    public Result<Long> createPricingRule(@RequestBody PricingRule rule) {
        return Result.success(billingService.createPricingRule(rule));
    }

    @PutMapping("/pricing-rules/{id}")
    public Result<Boolean> updatePricingRule(@PathVariable Long id, @RequestBody PricingRule rule) {
        rule.setId(id);
        return Result.success(billingService.updatePricingRule(rule));
    }

    @GetMapping("/pricing-rules")
    public Result<List<PricingRule>> listPricingRules() {
        return Result.success(billingService.listPricingRules());
    }

    @GetMapping("/pricing-rules/{id}")
    public Result<PricingRule> getPricingRule(@PathVariable Long id) {
        return Result.success(billingService.getPricingRule(id));
    }

    @DeleteMapping("/pricing-rules/{id}")
    public Result<Boolean> deletePricingRule(@PathVariable Long id) {
        return Result.success(billingService.deletePricingRule(id));
    }

    @PostMapping("/cycles/generate")
    public Result<Long> generateBillingCycle(
            @RequestParam Long tenantId,
            @RequestParam String cycleType) {
        return Result.success(billingService.generateBillingCycle(tenantId, cycleType));
    }

    @GetMapping("/cycles/{id}")
    public Result<BillingCycle> getBillingCycle(@PathVariable Long id) {
        return Result.success(billingService.getBillingCycle(id));
    }

    @GetMapping("/cycles")
    public Result<List<BillingCycle>> listBillingCycles(
            @RequestParam Long tenantId,
            @RequestParam(required = false) String status) {
        return Result.success(billingService.listBillingCycles(tenantId, status));
    }

    @GetMapping("/cycles/{cycleId}/items")
    public Result<List<BillingItem>> listBillingItems(@PathVariable Long cycleId) {
        return Result.success(billingService.listBillingItems(cycleId));
    }

    @GetMapping("/usage/cost")
    public Result<BigDecimal> calculateUsageCost(
            @RequestParam Long tenantId,
            @RequestParam String resourceType,
            @RequestParam Long startTime,
            @RequestParam Long endTime) {
        return Result.success(billingService.calculateUsageCost(tenantId, resourceType, startTime, endTime));
    }

    @GetMapping("/summary")
    public Result<Map<String, Object>> getTenantBillingSummary(
            @RequestParam Long tenantId,
            @RequestParam String cycleCode) {
        return Result.success(billingService.getTenantBillingSummary(tenantId, cycleCode));
    }

    @PostMapping("/cycles/{cycleId}/pay")
    public Result<Boolean> processPayment(
            @PathVariable Long cycleId,
            @RequestParam BigDecimal amount) {
        return Result.success(billingService.processPayment(cycleId, amount));
    }

    @GetMapping("/estimate")
    public Result<Map<String, Object>> getPricingEstimate(
            @RequestParam Long tenantId,
            @RequestBody Map<String, Long> estimatedUsage) {
        return Result.success(billingService.getPricingEstimate(tenantId, estimatedUsage));
    }
}
