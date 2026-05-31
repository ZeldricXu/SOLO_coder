package com.contractai.tenant.service;

import com.contractai.tenant.entity.TenantQuota;
import com.contractai.tenant.mapper.TenantQuotaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuotaResetStrategy {

    private final TenantQuotaMapper tenantQuotaMapper;

    public void checkAndResetIfNeeded(TenantQuota quota) {
        if (!needReset(quota)) {
            return;
        }

        tenantQuotaMapper.resetQuota(quota.getId());
        log.info("重置租户配额: tenantId={}, resourceType={}", quota.getTenantId(), quota.getResourceType());
    }

    private boolean needReset(TenantQuota quota) {
        if (quota.getResetPeriod() == null || quota.getLastResetAt() == null) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastReset = quota.getLastResetAt();

        return switch (quota.getResetPeriod()) {
            case "daily" -> !lastReset.toLocalDate().equals(now.toLocalDate());
            case "monthly" -> lastReset.getMonth() != now.getMonth() || lastReset.getYear() != now.getYear();
            case "yearly" -> lastReset.getYear() != now.getYear();
            default -> false;
        };
    }
}
