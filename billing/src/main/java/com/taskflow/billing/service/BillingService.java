package com.taskflow.billing.service;

import com.taskflow.billing.model.Bill;
import com.taskflow.billing.model.BillItem;
import com.taskflow.billing.model.UsageSummary;
import com.taskflow.common.utils.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final UsageCollector usageCollector;
    private final PricingService pricingService;

    public Bill generateBill(String tenantId, String billingPeriod) {
        log.info("Generating bill for tenant: {}, period: {}", tenantId, billingPeriod);

        YearMonth period = YearMonth.parse(billingPeriod, DateTimeFormatter.ofPattern("yyyy-MM"));
        LocalDateTime periodStart = period.atDay(1).atStartOfDay();
        LocalDateTime periodEnd = period.atEndOfMonth().atTime(23, 59, 59);

        List<BillItem> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        Map<String, BigDecimal> usageByResource = new HashMap<>();
        usageByResource.put("task_executions", usageCollector.getCurrentUsage(tenantId, "task_executions"));
        usageByResource.put("compute_minutes", usageCollector.getCurrentUsage(tenantId, "compute_minutes"));
        usageByResource.put("api_calls", usageCollector.getCurrentUsage(tenantId, "api_calls"));
        usageByResource.put("storage_gb", usageCollector.getCurrentUsage(tenantId, "storage_gb"));

        for (Map.Entry<String, BigDecimal> entry : usageByResource.entrySet()) {
            String resourceType = entry.getKey();
            BigDecimal usage = entry.getValue();
            BigDecimal cost = pricingService.calculateCost(resourceType, usage);

            if (cost.compareTo(BigDecimal.ZERO) > 0 || usage.compareTo(BigDecimal.ZERO) > 0) {
                BillItem item = BillItem.builder()
                        .itemId(IdGenerator.generateId("item"))
                        .resourceType(resourceType)
                        .resourceName(getResourceName(resourceType))
                        .quantity(usage)
                        .unit(getResourceUnit(resourceType))
                        .unitPrice(pricingService.getRule(resourceType) != null
                                ? pricingService.getRule(resourceType).getUnitPrice()
                                : BigDecimal.ZERO)
                        .amount(cost)
                        .description(getResourceDescription(resourceType))
                        .build();
                items.add(item);
                totalAmount = totalAmount.add(cost);
            }
        }

        BigDecimal discountAmount = calculateDiscount(tenantId, totalAmount);
        BigDecimal payableAmount = totalAmount.subtract(discountAmount);

        return Bill.builder()
                .billId(IdGenerator.generateId("bill"))
                .tenantId(tenantId)
                .billingPeriod(billingPeriod)
                .totalAmount(totalAmount)
                .discountAmount(discountAmount)
                .payableAmount(payableAmount)
                .paidAmount(BigDecimal.ZERO)
                .status("draft")
                .issuedAt(LocalDateTime.now())
                .dueDate(periodEnd.plusDays(15))
                .items(items)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public UsageSummary getUsageSummary(String tenantId, String period) {
        Map<String, BigDecimal> usageByResource = new HashMap<>();
        usageByResource.put("task_executions", usageCollector.getCurrentUsage(tenantId, "task_executions"));
        usageByResource.put("compute_minutes", usageCollector.getCurrentUsage(tenantId, "compute_minutes"));
        usageByResource.put("api_calls", usageCollector.getCurrentUsage(tenantId, "api_calls"));
        usageByResource.put("storage_gb", usageCollector.getCurrentUsage(tenantId, "storage_gb"));

        BigDecimal estimatedCost = BigDecimal.ZERO;
        Map<String, BigDecimal> usagePercentage = new HashMap<>();
        BigDecimal totalFreeQuota = BigDecimal.ZERO;

        for (Map.Entry<String, BigDecimal> entry : usageByResource.entrySet()) {
            String resourceType = entry.getKey();
            BigDecimal usage = entry.getValue();
            estimatedCost = estimatedCost.add(pricingService.calculateCost(resourceType, usage));

            var rule = pricingService.getRule(resourceType);
            if (rule != null && rule.getFreeQuota().compareTo(BigDecimal.ZERO) > 0) {
                double percentage = usage.divide(rule.getFreeQuota(), 4, java.math.RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).doubleValue();
                usagePercentage.put(resourceType, new BigDecimal(Math.min(percentage, 100)));
                totalFreeQuota = totalFreeQuota.add(rule.getFreeQuota());
            }
        }

        return UsageSummary.builder()
                .tenantId(tenantId)
                .period(period)
                .usageByResource(usageByResource)
                .estimatedCost(estimatedCost)
                .totalFreeQuota(totalFreeQuota)
                .usagePercentage(usagePercentage)
                .build();
    }

    private BigDecimal calculateDiscount(String tenantId, BigDecimal totalAmount) {
        if (totalAmount.compareTo(new BigDecimal("1000")) >= 0) {
            return totalAmount.multiply(new BigDecimal("0.10"));
        } else if (totalAmount.compareTo(new BigDecimal("500")) >= 0) {
            return totalAmount.multiply(new BigDecimal("0.05"));
        }
        return BigDecimal.ZERO;
    }

    private String getResourceName(String resourceType) {
        return switch (resourceType) {
            case "task_executions" -> "任务执行次数";
            case "compute_minutes" -> "计算时长";
            case "api_calls" -> "API调用次数";
            case "storage_gb" -> "存储容量";
            default -> resourceType;
        };
    }

    private String getResourceUnit(String resourceType) {
        return switch (resourceType) {
            case "task_executions" -> "次";
            case "compute_minutes" -> "分钟";
            case "api_calls" -> "次";
            case "storage_gb" -> "GB";
            default -> "单位";
        };
    }

    private String getResourceDescription(String resourceType) {
        return switch (resourceType) {
            case "task_executions" -> "任务调度与执行次数";
            case "compute_minutes" -> "计算资源使用时长";
            case "api_calls" -> "API接口调用次数";
            case "storage_gb" -> "数据存储容量";
            default -> "";
        };
    }

    public void issueBill(String tenantId, String billingPeriod) {
        Bill bill = generateBill(tenantId, billingPeriod);
        log.info("Bill issued: {}", bill.getBillId());
    }

    public List<Bill> getBills(String tenantId) {
        return List.of();
    }

    public Bill getBill(String tenantId, String billId) {
        return null;
    }
}
