package com.orchestration.tenant.service;

import com.orchestration.persistence.entity.Tenant;
import com.orchestration.persistence.entity.TenantConfig;
import com.orchestration.persistence.entity.TenantResourceQuota;
import java.util.List;
import java.util.Map;

public interface TenantService {

    Long createTenant(Tenant tenant);

    boolean updateTenant(Tenant tenant);

    Tenant getTenant(Long id);

    Tenant getTenantByCode(String tenantCode);

    List<Tenant> listTenants(Integer page, Integer size);

    boolean enableTenant(Long id);

    boolean disableTenant(Long id);

    Long setConfig(Long tenantId, String configKey, String configValue, String configType, String description);

    List<TenantConfig> listConfigs(Long tenantId);

    String getConfigValue(Long tenantId, String configKey);

    boolean deleteConfig(Long id);

    Long setQuota(Long tenantId, String resourceType, Long quotaLimit, String unit);

    List<TenantResourceQuota> listQuotas(Long tenantId);

    boolean consumeQuota(Long tenantId, String resourceType, Long amount);

    boolean releaseQuota(Long tenantId, String resourceType, Long amount);

    Map<String, Object> getTenantUsage(Long tenantId);

    boolean checkQuotaWarning(Long tenantId);
}
