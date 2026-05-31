package com.contractai.metering.controller;

import com.contractai.common.dto.PageQuery;
import com.contractai.common.dto.PageResult;
import com.contractai.common.result.ApiResponse;
import com.contractai.metering.dto.*;
import com.contractai.metering.entity.*;
import com.contractai.metering.service.MeteringService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/metering")
@RequiredArgsConstructor
public class MeteringController {

    private final MeteringService meteringService;

    @PostMapping("/usage")
    public ApiResponse<UsageRecord> recordUsage(@RequestBody UsageRecordCreateDTO dto) {
        return ApiResponse.created(meteringService.recordUsage(dto));
    }

    @GetMapping("/usage")
    public ApiResponse<PageResult<UsageRecord>> listUsageRecords(
            @ModelAttribute PageQuery pageQuery,
            @ModelAttribute UsageQueryDTO query) {
        return ApiResponse.success(meteringService.listUsageRecords(pageQuery, query));
    }

    @GetMapping("/usage/stats")
    public ApiResponse<List<UsageStatsDTO>> getUsageStats(@ModelAttribute UsageQueryDTO query) {
        return ApiResponse.success(meteringService.getUsageStats(query));
    }

    @PostMapping("/price-rules")
    public ApiResponse<PriceRule> createPriceRule(@RequestBody PriceRuleCreateDTO dto) {
        return ApiResponse.created(meteringService.createPriceRule(dto));
    }

    @GetMapping("/price-rules")
    public ApiResponse<List<PriceRule>> listPriceRules() {
        return ApiResponse.success(meteringService.listPriceRules());
    }

    @PostMapping("/billing-plans")
    public ApiResponse<BillingPlan> createBillingPlan(@RequestBody BillingPlanCreateDTO dto) {
        return ApiResponse.created(meteringService.createBillingPlan(dto));
    }

    @GetMapping("/billing-plans")
    public ApiResponse<List<BillingPlan>> listBillingPlans() {
        return ApiResponse.success(meteringService.listBillingPlans());
    }

    @GetMapping("/bills")
    public ApiResponse<PageResult<Bill>> listBills(
            @ModelAttribute PageQuery pageQuery,
            @ModelAttribute BillingQueryDTO query) {
        return ApiResponse.success(meteringService.listBills(pageQuery, query));
    }

    @GetMapping("/bills/{id}")
    public ApiResponse<Bill> getBill(@PathVariable Long id) {
        return ApiResponse.success(meteringService.getBill(id));
    }

    @GetMapping("/bills/{id}/items")
    public ApiResponse<List<BillItem>> getBillItems(@PathVariable Long id) {
        return ApiResponse.success(meteringService.getBillItems(id));
    }

    @PostMapping("/bills/payment")
    public ApiResponse<Bill> processPayment(@RequestBody BillPaymentDTO dto) {
        return ApiResponse.success(meteringService.processPayment(dto));
    }

    @GetMapping("/calculate-cost")
    public ApiResponse<BigDecimal> calculateCost(
            @RequestParam String resourceType,
            @RequestParam Long usageAmount) {
        return ApiResponse.success(meteringService.calculateCost(resourceType, usageAmount));
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> getBillingDashboard() {
        return ApiResponse.success(meteringService.getBillingDashboard());
    }

    @PostMapping("/bills/generate")
    public ApiResponse<Bill> generateBill(
            @RequestParam Long tenantId,
            @RequestParam Integer year,
            @RequestParam Integer month) {
        return ApiResponse.success(meteringService.generateBill(
                tenantId, java.time.YearMonth.of(year, month)));
    }
}
