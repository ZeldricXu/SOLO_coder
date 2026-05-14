package com.supplychain.analytics.controller;

import com.supplychain.common.dto.ResponseResult;
import com.supplychain.common.entity.PurchaseStatistics;
import com.supplychain.analytics.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "数据分析", description = "供应链数据统计分析接口")
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Operation(summary = "获取月度统计")
    @GetMapping("/monthly")
    public ResponseResult<List<PurchaseStatistics>> getMonthlyStatistics(
            @RequestParam(required = false) String startMonth,
            @RequestParam(required = false) String endMonth) {
        return ResponseResult.success(analyticsService.getMonthlyStatistics(startMonth, endMonth));
    }

    @Operation(summary = "获取当月统计")
    @GetMapping("/current")
    public ResponseResult<PurchaseStatistics> getCurrentMonthStats() {
        return ResponseResult.success(analyticsService.getCurrentMonthStats());
    }

    @Operation(summary = "获取仪表盘数据")
    @GetMapping("/dashboard")
    public ResponseResult<Map<String, Object>> getDashboardStats() {
        return ResponseResult.success(analyticsService.getDashboardStats());
    }

    @Operation(summary = "获取供应商分析")
    @GetMapping("/suppliers")
    public ResponseResult<Map<String, Object>> getSupplierAnalysis() {
        return ResponseResult.success(analyticsService.getSupplierAnalysis());
    }

    @Operation(summary = "获取采购趋势")
    @GetMapping("/trend")
    public ResponseResult<Map<String, Object>> getPurchaseTrend(
            @RequestParam(defaultValue = "6") int months) {
        return ResponseResult.success(analyticsService.getPurchaseTrend(months));
    }

    @Operation(summary = "记录采购统计")
    @PostMapping("/purchase")
    public ResponseResult<Void> recordPurchase(@RequestBody Map<String, Object> request) {
        java.math.BigDecimal amount = request.containsKey("amount") 
            ? new java.math.BigDecimal(request.get("amount").toString()) 
            : java.math.BigDecimal.ZERO;
        int supplierCount = ((Number) request.getOrDefault("supplierCount", 1)).intValue();
        analyticsService.recordPurchase(amount, supplierCount);
        return ResponseResult.success();
    }
}
