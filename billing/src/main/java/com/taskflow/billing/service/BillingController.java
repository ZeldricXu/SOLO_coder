package com.taskflow.billing.service;

import com.taskflow.billing.model.Bill;
import com.taskflow.billing.model.PricingRule;
import com.taskflow.billing.model.UsageSummary;
import com.taskflow.common.model.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;
    private final PricingService pricingService;
    private final UsageCollector usageCollector;

    @GetMapping("/usage/summary")
    public Mono<Result<UsageSummary>> getUsageSummary(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam(defaultValue = "#{T(java.time.LocalDateTime).now().format(T(java.time.format.DateTimeFormatter).ofPattern('yyyy-MM'))}") String period) {
        return Mono.fromCallable(() -> Result.success(billingService.getUsageSummary(tenantId, period)));
    }

    @PostMapping("/bills/generate")
    public Mono<Result<Bill>> generateBill(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody Map<String, String> request) {
        return Mono.fromCallable(() -> {
            String billingPeriod = request.getOrDefault("billingPeriod",
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")));
            return Result.success(billingService.generateBill(tenantId, billingPeriod));
        });
    }

    @GetMapping("/bills")
    public Mono<Result<List<Bill>>> getBills(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Mono.fromCallable(() -> Result.success(billingService.getBills(tenantId)));
    }

    @GetMapping("/bills/{billId}")
    public Mono<Result<Bill>> getBill(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String billId) {
        return Mono.fromCallable(() -> Result.success(billingService.getBill(tenantId, billId)));
    }

    @GetMapping("/pricing")
    public Mono<Result<Map<String, PricingRule>>> getPricingRules() {
        return Mono.fromCallable(() -> Result.success(pricingService.getAllRules()));
    }

    @PostMapping("/usage/record")
    public Mono<Result<Void>> recordUsage(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody Map<String, Object> request) {
        return Mono.fromCallable(() -> {
            String resourceType = (String) request.get("resourceType");
            double amount = ((Number) request.get("amount")).doubleValue();
            @SuppressWarnings("unchecked")
            Map<String, String> tags = (Map<String, String>) request.get("tags");
            usageCollector.recordUsage(tenantId, resourceType, amount, tags);
            return Result.success(null);
        });
    }
}
