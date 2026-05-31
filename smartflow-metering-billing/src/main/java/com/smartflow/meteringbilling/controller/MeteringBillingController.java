package com.smartflow.meteringbilling.controller;

import com.smartflow.common.base.Result;
import com.smartflow.persistence.entity.BillingInvoice;
import com.smartflow.persistence.entity.TenantUsage;
import com.smartflow.meteringbilling.service.MeteringBillingService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/metering")
@RequiredArgsConstructor
public class MeteringBillingController {

    private final MeteringBillingService meteringBillingService;

    @PostMapping("/usage")
    public Result<TenantUsage> recordUsage(
            @RequestParam Long tenantId,
            @RequestParam String resourceType,
            @RequestParam Long amount,
            @RequestParam(required = false) String dimension) {
        TenantUsage usage = meteringBillingService.recordUsage(tenantId, resourceType, amount, dimension);
        return Result.success(usage);
    }

    @GetMapping("/usage/summary")
    public Result<Map<String, Object>> getUsageSummary(
            @RequestParam Long tenantId,
            @RequestParam Integer year,
            @RequestParam Integer month) {
        Map<String, Object> summary = meteringBillingService.getTenantUsageSummary(tenantId, year, month);
        return Result.success(summary);
    }

    @GetMapping("/usage/records")
    public Result<List<TenantUsage>> getUsageRecords(
            @RequestParam Long tenantId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        List<TenantUsage> records = meteringBillingService.getUsageRecords(tenantId, resourceType, startTime, endTime);
        return Result.success(records);
    }

    @PostMapping("/invoice/generate")
    public Result<BillingInvoice> generateInvoice(
            @RequestParam Long tenantId,
            @RequestParam Integer year,
            @RequestParam Integer month) {
        BillingInvoice invoice = meteringBillingService.generateMonthlyInvoice(tenantId, year, month);
        return Result.success(invoice);
    }

    @GetMapping("/invoice/list")
    public Result<Map<String, Object>> getInvoiceList(
            @RequestParam Long tenantId,
            @RequestParam(required = false) Integer status) {
        Map<String, Object> result = meteringBillingService.getInvoiceList(tenantId, status);
        return Result.success(result);
    }

    @PostMapping("/invoice/pay/{invoiceId}")
    public Result<Boolean> payInvoice(@PathVariable Long invoiceId) {
        boolean success = meteringBillingService.payInvoice(invoiceId);
        return Result.success(success);
    }
}
