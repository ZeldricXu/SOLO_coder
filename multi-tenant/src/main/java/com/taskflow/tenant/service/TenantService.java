package com.taskflow.tenant.service;

import com.taskflow.common.exception.ResourceNotFoundException;
import com.taskflow.tenant.model.Tenant;
import com.taskflow.tenant.model.TenantConfig;
import com.taskflow.tenant.model.TenantQuota;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TenantService {

    private final Map<String, Tenant> tenantStore = new ConcurrentHashMap<>();
    private final Map<String, Map<String, TenantConfig>> configStore = new ConcurrentHashMap<>();
    private final Map<String, Map<String, TenantQuota>> quotaStore = new ConcurrentHashMap<>();

    private final LoadingCache<String, Tenant> tenantCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1000)
            .build(this::loadTenantFromStore);

    public Tenant getTenant(String tenantId) {
        Tenant tenant = tenantCache.get(tenantId);
        if (tenant == null) {
            throw new ResourceNotFoundException("Tenant", tenantId);
        }
        return tenant;
    }

    private Tenant loadTenantFromStore(String tenantId) {
        return tenantStore.get(tenantId);
    }

    public void registerTenant(Tenant tenant) {
        tenantStore.put(tenant.getTenantId(), tenant);
        configStore.put(tenant.getTenantId(), new ConcurrentHashMap<>());
        quotaStore.put(tenant.getTenantId(), new ConcurrentHashMap<>());
        tenantCache.put(tenant.getTenantId(), tenant);
        log.info("Tenant registered: {}", tenant.getTenantId());
    }

    public boolean isTenantActive(String tenantId) {
        try {
            Tenant tenant = getTenant(tenantId);
            return "active".equals(tenant.getStatus());
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    public TenantConfig getConfig(String tenantId, String configKey) {
        Map<String, TenantConfig> configs = configStore.get(tenantId);
        if (configs == null) return null;
        return configs.get(configKey);
    }

    public void setConfig(String tenantId, TenantConfig config) {
        configStore.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>())
                .put(config.getConfigKey(), config);
    }

    public TenantQuota getQuota(String tenantId, String resourceType) {
        Map<String, TenantQuota> quotas = quotaStore.get(tenantId);
        if (quotas == null) return null;
        return quotas.get(resourceType);
    }

    public void updateQuotaUsage(String tenantId, String resourceType, long increment) {
        Map<String, TenantQuota> quotas = quotaStore.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>());
        quotas.compute(resourceType, (k, existing) -> {
            if (existing == null) {
                existing = new TenantQuota();
                existing.setTenantId(tenantId);
                existing.setResourceType(resourceType);
                existing.setMaxLimit(1000);
                existing.setUnit("units");
            }
            existing.setCurrentUsage(existing.getCurrentUsage() + increment);
            return existing;
        });
    }

    public boolean checkQuota(String tenantId, String resourceType, long requested) {
        TenantQuota quota = getQuota(tenantId, resourceType);
        if (quota == null) return true;
        return quota.getCurrentUsage() + requested <= quota.getMaxLimit();
    }
}
