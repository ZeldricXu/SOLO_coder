package com.orchestration.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.orchestration.common.exception.BusinessException;
import com.orchestration.tenant.service.TenantService;
import com.orchestration.persistence.entity.Tenant;
import com.orchestration.persistence.entity.TenantConfig;
import com.orchestration.persistence.entity.TenantResourceQuota;
import com.orchestration.persistence.mapper.TenantConfigMapper;
import com.orchestration.persistence.mapper.TenantMapper;
import com.orchestration.persistence.mapper.TenantResourceQuotaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {

    private final TenantMapper tenantMapper;
    private final TenantConfigMapper configMapper;
    private final TenantResourceQuotaMapper quotaMapper;

    @Override
    public Long createTenant(Tenant tenant) {
        Tenant existing = tenantMapper.selectOne(
                new LambdaQueryWrapper<Tenant>().eq(Tenant::getTenantCode, tenant.getTenantCode())
        );
        if (existing != null) {
            throw new BusinessException("租户编码已存在");
        }
        tenantMapper.insert(tenant);

        initDefaultQuotas(tenant.getId());
        initDefaultConfigs(tenant.getId());

        return tenant.getId();
    }

    private void initDefaultQuotas(Long tenantId) {
        TenantResourceQuota taskQuota = new TenantResourceQuota();
        taskQuota.setTenantId(tenantId);
        taskQuota.setResourceType("task_execution");
        taskQuota.setQuotaLimit(10000L);
        taskQuota.setQuotaUsed(0L);
        taskQuota.setUnit("次");
        taskQuota.setWarningThreshold(new BigDecimal("0.8"));
        quotaMapper.insert(taskQuota);

        TenantResourceQuota storageQuota = new TenantResourceQuota();
        storageQuota.setTenantId(tenantId);
        storageQuota.setResourceType("storage");
        storageQuota.setQuotaLimit(10737418240L);
        storageQuota.setQuotaUsed(0L);
        storageQuota.setUnit("bytes");
        storageQuota.setWarningThreshold(new BigDecimal("0.8"));
        quotaMapper.insert(storageQuota);

        TenantResourceQuota apiQuota = new TenantResourceQuota();
        apiQuota.setTenantId(tenantId);
        apiQuota.setResourceType("api_calls");
        apiQuota.setQuotaLimit(100000L);
        apiQuota.setQuotaUsed(0L);
        apiQuota.setUnit("次");
        apiQuota.setWarningThreshold(new BigDecimal("0.8"));
        quotaMapper.insert(apiQuota);
    }

    private void initDefaultConfigs(Long tenantId) {
        TenantConfig config = new TenantConfig();
        config.setTenantId(tenantId);
        config.setConfigKey("default_priority");
        config.setConfigValue("5");
        config.setConfigType("number");
        config.setDescription("默认任务优先级");
        configMapper.insert(config);
    }

    @Override
    public boolean updateTenant(Tenant tenant) {
        return tenantMapper.updateById(tenant) > 0;
    }

    @Override
    public Tenant getTenant(Long id) {
        return tenantMapper.selectById(id);
    }

    @Override
    public Tenant getTenantByCode(String tenantCode) {
        return tenantMapper.selectOne(
                new LambdaQueryWrapper<Tenant>().eq(Tenant::getTenantCode, tenantCode)
        );
    }

    @Override
    public List<Tenant> listTenants(Integer page, Integer size) {
        Page<Tenant> pageResult = tenantMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<Tenant>().orderByDesc(Tenant::getCreatedAt)
        );
        return pageResult.getRecords();
    }

    @Override
    public boolean enableTenant(Long id) {
        Tenant tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BusinessException("租户不存在");
        }
        tenant.setStatus(1);
        return tenantMapper.updateById(tenant) > 0;
    }

    @Override
    public boolean disableTenant(Long id) {
        Tenant tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BusinessException("租户不存在");
        }
        tenant.setStatus(0);
        return tenantMapper.updateById(tenant) > 0;
    }

    @Override
    public Long setConfig(Long tenantId, String configKey, String configValue, String configType, String description) {
        TenantConfig existing = configMapper.selectOne(
                new LambdaQueryWrapper<TenantConfig>()
                        .eq(TenantConfig::getTenantId, tenantId)
                        .eq(TenantConfig::getConfigKey, configKey)
        );

        if (existing != null) {
            existing.setConfigValue(configValue);
            existing.setConfigType(configType != null ? configType : existing.getConfigType());
            existing.setDescription(description != null ? description : existing.getDescription());
            configMapper.updateById(existing);
            return existing.getId();
        } else {
            TenantConfig config = new TenantConfig();
            config.setTenantId(tenantId);
            config.setConfigKey(configKey);
            config.setConfigValue(configValue);
            config.setConfigType(configType != null ? configType : "string");
            config.setDescription(description);
            configMapper.insert(config);
            return config.getId();
        }
    }

    @Override
    public List<TenantConfig> listConfigs(Long tenantId) {
        return configMapper.selectList(
                new LambdaQueryWrapper<TenantConfig>()
                        .eq(TenantConfig::getTenantId, tenantId)
                        .orderByAsc(TenantConfig::getConfigKey)
        );
    }

    @Override
    public String getConfigValue(Long tenantId, String configKey) {
        TenantConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<TenantConfig>()
                        .eq(TenantConfig::getTenantId, tenantId)
                        .eq(TenantConfig::getConfigKey, configKey)
        );
        return config != null ? config.getConfigValue() : null;
    }

    @Override
    public boolean deleteConfig(Long id) {
        return configMapper.deleteById(id) > 0;
    }

    @Override
    public Long setQuota(Long tenantId, String resourceType, Long quotaLimit, String unit) {
        TenantResourceQuota existing = quotaMapper.selectOne(
                new LambdaQueryWrapper<TenantResourceQuota>()
                        .eq(TenantResourceQuota::getTenantId, tenantId)
                        .eq(TenantResourceQuota::getResourceType, resourceType)
        );

        if (existing != null) {
            existing.setQuotaLimit(quotaLimit);
            existing.setUnit(unit != null ? unit : existing.getUnit());
            quotaMapper.updateById(existing);
            return existing.getId();
        } else {
            TenantResourceQuota quota = new TenantResourceQuota();
            quota.setTenantId(tenantId);
            quota.setResourceType(resourceType);
            quota.setQuotaLimit(quotaLimit);
            quota.setQuotaUsed(0L);
            quota.setUnit(unit);
            quota.setWarningThreshold(new BigDecimal("0.8"));
            quotaMapper.insert(quota);
            return quota.getId();
        }
    }

    @Override
    public List<TenantResourceQuota> listQuotas(Long tenantId) {
        return quotaMapper.selectList(
                new LambdaQueryWrapper<TenantResourceQuota>()
                        .eq(TenantResourceQuota::getTenantId, tenantId)
        );
    }

    @Override
    @Transactional
    public boolean consumeQuota(Long tenantId, String resourceType, Long amount) {
        TenantResourceQuota quota = quotaMapper.selectOne(
                new LambdaQueryWrapper<TenantResourceQuota>()
                        .eq(TenantResourceQuota::getTenantId, tenantId)
                        .eq(TenantResourceQuota::getResourceType, resourceType)
        );

        if (quota == null) {
            return true;
        }

        long newUsed = quota.getQuotaUsed() + amount;
        if (newUsed > quota.getQuotaLimit()) {
            throw new BusinessException("资源配额不足: " + resourceType);
        }

        quota.setQuotaUsed(newUsed);
        quotaMapper.updateById(quota);

        if (checkQuotaWarning(tenantId)) {
            log.warn("租户 {} 资源 {} 使用率超过告警阈值", tenantId, resourceType);
        }

        return true;
    }

    @Override
    @Transactional
    public boolean releaseQuota(Long tenantId, String resourceType, Long amount) {
        TenantResourceQuota quota = quotaMapper.selectOne(
                new LambdaQueryWrapper<TenantResourceQuota>()
                        .eq(TenantResourceQuota::getTenantId, tenantId)
                        .eq(TenantResourceQuota::getResourceType, resourceType)
        );

        if (quota != null) {
            quota.setQuotaUsed(Math.max(0, quota.getQuotaUsed() - amount));
            quotaMapper.updateById(quota);
        }
        return true;
    }

    @Override
    public Map<String, Object> getTenantUsage(Long tenantId) {
        Map<String, Object> usage = new HashMap<>();
        List<TenantResourceQuota> quotas = listQuotas(tenantId);

        List<Map<String, Object>> quotaDetails = new java.util.ArrayList<>();
        for (TenantResourceQuota quota : quotas) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("resourceType", quota.getResourceType());
            detail.put("quotaLimit", quota.getQuotaLimit());
            detail.put("quotaUsed", quota.getQuotaUsed());
            detail.put("unit", quota.getUnit());
            detail.put("usageRate", quota.getQuotaLimit() > 0
                    ? (double) quota.getQuotaUsed() / quota.getQuotaLimit()
                    : 0);
            quotaDetails.add(detail);
        }

        usage.put("tenantId", tenantId);
        usage.put("quotas", quotaDetails);
        usage.put("quotaCount", quotas.size());

        return usage;
    }

    @Override
    public boolean checkQuotaWarning(Long tenantId) {
        List<TenantResourceQuota> quotas = listQuotas(tenantId);
        for (TenantResourceQuota quota : quotas) {
            if (quota.getQuotaLimit() > 0) {
                double usageRate = (double) quota.getQuotaUsed() / quota.getQuotaLimit();
                BigDecimal threshold = quota.getWarningThreshold() != null ? quota.getWarningThreshold() : new BigDecimal("0.8");
                if (usageRate >= threshold.doubleValue()) {
                    return true;
                }
            }
        }
        return false;
    }
}
