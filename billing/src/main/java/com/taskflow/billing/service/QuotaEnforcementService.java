package com.taskflow.billing.service;

import com.taskflow.billing.model.UsageSummary;
import com.taskflow.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaEnforcementService {

    private final BillingService billingService;
    private final TenantService tenantService;

    public boolean checkQuota(String tenantId, String resourceType, long requestedAmount) {
        UsageSummary summary = billingService.getUsageSummary(tenantId, getCurrentPeriod());
        var usagePercentage = summary.getUsagePercentage().get(resourceType);

        if (usagePercentage != null && usagePercentage.doubleValue() >= 100) {
            log.warn("Quota exceeded for tenant: {}, resource: {}", tenantId, resourceType);
            return false;
        }

        return tenantService.checkQuota(tenantId, resourceType, requestedAmount);
    }

    public boolean enforceQuota(String tenantId, String resourceType, long requestedAmount) {
        if (!checkQuota(tenantId, resourceType, requestedAmount)) {
            throw new RuntimeException("Quota exceeded for resource: " + resourceType);
        }
        return true;
    }

    @Scheduled(cron = "0 */30 * * * *")
    public void monitorQuotaUsage() {
        log.debug("Monitoring quota usage...");
    }

    @Scheduled(cron = "0 0 10 * * *")
    public void sendQuotaAlerts() {
        log.info("Checking quota alerts...");
    }

    private String getCurrentPeriod() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }
}
