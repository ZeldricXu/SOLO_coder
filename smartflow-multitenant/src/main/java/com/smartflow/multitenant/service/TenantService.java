package com.smartflow.multitenant.service;

import com.smartflow.common.context.TenantContext;
import com.smartflow.common.exception.BusinessException;
import com.smartflow.common.utils.IdGenerator;
import com.smartflow.common.utils.JsonUtils;
import com.smartflow.persistence.entity.Tenant;
import com.smartflow.persistence.entity.TenantConfig;
import com.smartflow.persistence.entity.TenantQuota;
import com.smartflow.persistence.mapper.TenantConfigMapper;
import com.smartflow.persistence.mapper.TenantMapper;
import com.smartflow.persistence.mapper.TenantQuotaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantMapper tenantMapper;
    private final TenantConfigMapper configMapper;
    private final TenantQuotaMapper quotaMapper;

    @Transactional
    public Tenant createTenant(Tenant tenant) {
        Tenant existing = tenantMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantCode, tenant.getTenantCode())
        );
        if (existing != null) {
            throw new BusinessException("租户编码已存在");
        }

        tenant.setId(IdGenerator.generateId());
        tenant.setStatus(1);
        tenantMapper.insert(tenant);

        initDefaultConfigs(tenant.getId());
        initDefaultQuotas(tenant.getId());

        return tenant;
    }

    private void initDefaultConfigs(Long tenantId) {
        Map<String, String> defaultConfigs = new HashMap<>();
        defaultConfigs.put("ticket.auto_assignment", "true");
        defaultConfigs.put("ticket.load_balance_strategy", "LEAST_LOAD");
        defaultConfigs.put("approval.default_strategy", "ANY");
        defaultConfigs.put("sla.warning_threshold", "0.8");
        defaultConfigs.put("ui.theme", "default");
        defaultConfigs.put("notification.email", "true");
        defaultConfigs.put("notification.sms", "false");

        for (Map.Entry<String, String> entry : defaultConfigs.entrySet()) {
            TenantConfig config = new TenantConfig();
            config.setId(IdGenerator.generateId());
            config.setTenantId(tenantId);
            config.setConfigKey(entry.getKey());
            config.setConfigValue(entry.getValue());
            config.setConfigType("STRING");
            config.setEnabled(1);
            configMapper.insert(config);
        }
    }

    private void initDefaultQuotas(Long tenantId) {
        Map<String, Long> defaultQuotas = new HashMap<>();
        defaultQuotas.put("TICKET_COUNT", 10000L);
        defaultQuotas.put("STORAGE", 10737418240L);
        defaultQuotas.put("API_CALL", 1000000L);
        defaultQuotas.put("APPROVAL_FLOW", 1000L);
        defaultQuotas.put("DOCUMENT_COMPARE", 500L);

        for (Map.Entry<String, Long> entry : defaultQuotas.entrySet()) {
            TenantQuota quota = new TenantQuota();
            quota.setId(IdGenerator.generateId());
            quota.setTenantId(tenantId);
            quota.setResourceType(entry.getKey());
            quota.setQuotaLimit(entry.getValue());
            quota.setUsedAmount(0L);
            quota.setWarningThreshold((long) (entry.getValue() * 0.8));
            quota.setStatus(1);
            quotaMapper.insert(quota);
        }
    }

    public Tenant getTenant(Long tenantId) {
        Tenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BusinessException("租户不存在");
        }
        return tenant;
    }

    public Tenant getTenantByCode(String tenantCode) {
        Tenant tenant = tenantMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantCode, tenantCode)
        );
        if (tenant == null) {
            throw new BusinessException("租户不存在");
        }
        return tenant;
    }

    @Transactional
    public Tenant updateTenant(Tenant tenant) {
        Tenant existing = tenantMapper.selectById(tenant.getId());
        if (existing == null) {
            throw new BusinessException("租户不存在");
        }
        tenantMapper.updateById(tenant);
        return tenant;
    }

    public Map<String, Object> getTenantContext(Long tenantId) {
        Tenant tenant = getTenant(tenantId);
        List<TenantConfig> configs = configMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TenantConfig>()
                .eq(TenantConfig::getTenantId, tenantId)
                .eq(TenantConfig::getEnabled, 1)
        );
        List<TenantQuota> quotas = quotaMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TenantQuota>()
                .eq(TenantQuota::getTenantId, tenantId)
        );

        Map<String, Object> configMap = new HashMap<>();
        for (TenantConfig config : configs) {
            configMap.put(config.getConfigKey(), parseConfigValue(config));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("tenant", tenant);
        result.put("configs", configMap);
        result.put("quotas", quotas);
        return result;
    }

    private Object parseConfigValue(TenantConfig config) {
        String value = config.getConfigValue();
        String type = config.getConfigType();
        if ("BOOLEAN".equals(type)) {
            return Boolean.parseBoolean(value);
        } else if ("INTEGER".equals(type)) {
            return Integer.parseInt(value);
        } else if ("LONG".equals(type)) {
            return Long.parseLong(value);
        } else if ("DOUBLE".equals(type)) {
            return Double.parseDouble(value);
        } else if ("JSON".equals(type)) {
            return JsonUtils.parseMap(value);
        }
        return value;
    }

    @Transactional
    public TenantConfig setConfig(Long tenantId, String configKey, String configValue, String configType) {
        TenantConfig existing = configMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TenantConfig>()
                .eq(TenantConfig::getTenantId, tenantId)
                .eq(TenantConfig::getConfigKey, configKey)
        );

        if (existing != null) {
            existing.setConfigValue(configValue);
            existing.setConfigType(configType);
            configMapper.updateById(existing);
            return existing;
        } else {
            TenantConfig config = new TenantConfig();
            config.setId(IdGenerator.generateId());
            config.setTenantId(tenantId);
            config.setConfigKey(configKey);
            config.setConfigValue(configValue);
            config.setConfigType(configType);
            config.setEnabled(1);
            configMapper.insert(config);
            return config;
        }
    }

    public Object getConfig(Long tenantId, String configKey) {
        TenantConfig config = configMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TenantConfig>()
                .eq(TenantConfig::getTenantId, tenantId)
                .eq(TenantConfig::getConfigKey, configKey)
                .eq(TenantConfig::getEnabled, 1)
        );
        if (config == null) {
            return null;
        }
        return parseConfigValue(config);
    }

    @Transactional
    public TenantQuota setQuota(Long tenantId, String resourceType, Long quotaLimit) {
        TenantQuota existing = quotaMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TenantQuota>()
                .eq(TenantQuota::getTenantId, tenantId)
                .eq(TenantQuota::getResourceType, resourceType)
        );

        if (existing != null) {
            existing.setQuotaLimit(quotaLimit);
            existing.setWarningThreshold((long) (quotaLimit * 0.8));
            quotaMapper.updateById(existing);
            return existing;
        } else {
            TenantQuota quota = new TenantQuota();
            quota.setId(IdGenerator.generateId());
            quota.setTenantId(tenantId);
            quota.setResourceType(resourceType);
            quota.setQuotaLimit(quotaLimit);
            quota.setUsedAmount(0L);
            quota.setWarningThreshold((long) (quotaLimit * 0.8));
            quota.setStatus(1);
            quotaMapper.insert(quota);
            return quota;
        }
    }

    public boolean checkQuota(Long tenantId, String resourceType, Long requestedAmount) {
        TenantQuota quota = quotaMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TenantQuota>()
                .eq(TenantQuota::getTenantId, tenantId)
                .eq(TenantQuota::getResourceType, resourceType)
        );

        if (quota == null) {
            return true;
        }

        return quota.getUsedAmount() + requestedAmount <= quota.getQuotaLimit();
    }

    public List<Tenant> listTenants() {
        return tenantMapper.selectList(null);
    }

    public void setCurrentTenant(Long tenantId, Long userId) {
        TenantContext context = new TenantContext();
        context.setTenantId(tenantId);
        context.setUserId(userId);
        context.setTraceId(IdGenerator.generateTraceId());
        TenantContext.set(context);
    }

    public void clearCurrentTenant() {
        TenantContext.clear();
    }
}
