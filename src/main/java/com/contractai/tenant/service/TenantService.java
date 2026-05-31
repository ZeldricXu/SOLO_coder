package com.contractai.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.contractai.common.context.TenantContext;
import com.contractai.common.dto.PageQuery;
import com.contractai.common.dto.PageResult;
import com.contractai.common.exception.BusinessException;
import com.contractai.common.exception.ValidationException;
import com.contractai.tenant.dto.TenantConfigCreateDTO;
import com.contractai.tenant.dto.TenantCreateDTO;
import com.contractai.tenant.dto.TenantQuotaCreateDTO;
import com.contractai.tenant.entity.Tenant;
import com.contractai.tenant.entity.TenantConfig;
import com.contractai.tenant.entity.TenantQuota;
import com.contractai.tenant.mapper.TenantConfigMapper;
import com.contractai.tenant.mapper.TenantMapper;
import com.contractai.tenant.mapper.TenantQuotaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantMapper tenantMapper;
    private final TenantConfigMapper tenantConfigMapper;
    private final TenantQuotaMapper tenantQuotaMapper;
    private final QuotaResetStrategy quotaResetStrategy;

    @Transactional(rollbackFor = Exception.class)
    public Tenant createTenant(TenantCreateDTO dto) {
        validateTenantCreate(dto);
        checkTenantCodeUnique(dto.getTenantCode());

        Tenant tenant = buildTenant(dto);
        tenantMapper.insert(tenant);
        log.info("创建租户成功: id={}, code={}", tenant.getId(), tenant.getTenantCode());
        return tenant;
    }

    @Cacheable(value = "tenant", key = "#id")
    public Tenant getTenant(Long id) {
        Tenant tenant = tenantMapper.selectById(id);
        if (tenant == null || tenant.getDeleted() == 1) {
            throw new BusinessException(404, "租户不存在");
        }
        return tenant;
    }

    @Cacheable(value = "tenant", key = "#code")
    public Tenant getTenantByCode(String code) {
        Tenant tenant = tenantMapper.selectOne(
                new LambdaQueryWrapper<Tenant>().eq(Tenant::getTenantCode, code));
        if (tenant == null || tenant.getDeleted() == 1) {
            throw new BusinessException(404, "租户不存在");
        }
        return tenant;
    }

    public PageResult<Tenant> listTenants(PageQuery query) {
        Page<Tenant> page = new Page<>(query.getPageNum(), query.getPageSize());
        LambdaQueryWrapper<Tenant> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Tenant::getCreatedAt);
        tenantMapper.selectPage(page, wrapper);
        return new PageResult<>(page.getTotal(), page.getRecords(), query.getPageNum(), query.getPageSize());
    }

    @CacheEvict(value = "tenant", key = "#id")
    @Transactional(rollbackFor = Exception.class)
    public void updateTenantStatus(Long id, Integer status) {
        Tenant tenant = getTenant(id);
        tenant.setStatus(status);
        tenantMapper.updateById(tenant);
        log.info("更新租户状态: id={}, status={}", id, status);
    }

    @Transactional(rollbackFor = Exception.class)
    public TenantConfig createConfig(TenantConfigCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        validateConfigCreate(dto);

        String namespace = resolveNamespace(dto.getNamespace());
        Integer maxVersion = tenantConfigMapper.selectMaxVersion(tenantId, dto.getConfigId(), namespace);

        TenantConfig config = buildTenantConfig(dto, tenantId, namespace, maxVersion + 1);
        tenantConfigMapper.insert(config);
        log.info("创建租户配置成功: tenantId={}, configId={}, version={}", tenantId, dto.getConfigId(), config.getVersion());
        return config;
    }

    @Cacheable(value = "tenantConfig", key = "#tenantId + '_' + #configId + '_' + #namespace")
    public TenantConfig getConfig(Long tenantId, String configId, String namespace) {
        return tenantConfigMapper.selectLatestEnabled(tenantId, configId, resolveNamespace(namespace));
    }

    public Map<String, Object> getConfigParameters(Long tenantId, String configId, String namespace) {
        TenantConfig config = getConfig(tenantId, configId, namespace);
        return config != null ? config.getParameters() : Map.of();
    }

    @Transactional(rollbackFor = Exception.class)
    public TenantQuota createQuota(TenantQuotaCreateDTO dto) {
        Long tenantId = TenantContext.getTenantId();
        validateQuotaCreate(dto);
        checkQuotaUnique(tenantId, dto.getResourceType());

        TenantQuota quota = buildTenantQuota(dto, tenantId);
        tenantQuotaMapper.insert(quota);
        log.info("创建租户配额成功: tenantId={}, resourceType={}", tenantId, dto.getResourceType());
        return quota;
    }

    public List<TenantQuota> listQuotas(Long tenantId) {
        return tenantQuotaMapper.selectList(
                new LambdaQueryWrapper<TenantQuota>().eq(TenantQuota::getTenantId, tenantId));
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean consumeQuota(String resourceType, Long amount) {
        Long tenantId = TenantContext.getTenantId();
        TenantQuota quota = findQuota(tenantId, resourceType);

        if (quota == null) {
            log.warn("租户配额不存在, 跳过检查: tenantId={}, resourceType={}", tenantId, resourceType);
            return true;
        }

        quotaResetStrategy.checkAndResetIfNeeded(quota);

        if (!tryAtomicConsume(quota, amount, tenantId, resourceType)) {
            return false;
        }

        checkQuotaWarning(quota, tenantId, resourceType);
        return true;
    }

    public Map<String, Object> loadConfig(String namespace) {
        Long tenantId = TenantContext.getTenantId();
        return getConfigParameters(tenantId, "system", namespace);
    }

    private void validateTenantCreate(TenantCreateDTO dto) {
        if (!StringUtils.hasText(dto.getTenantCode())) {
            throw new ValidationException("租户编码不能为空");
        }
        if (!StringUtils.hasText(dto.getTenantName())) {
            throw new ValidationException("租户名称不能为空");
        }
    }

    private void checkTenantCodeUnique(String tenantCode) {
        Tenant exists = tenantMapper.selectOne(
                new LambdaQueryWrapper<Tenant>().eq(Tenant::getTenantCode, tenantCode));
        if (exists != null) {
            throw new BusinessException(400, "租户编码已存在");
        }
    }

    private Tenant buildTenant(TenantCreateDTO dto) {
        Tenant tenant = new Tenant();
        tenant.setTenantCode(dto.getTenantCode());
        tenant.setTenantName(dto.getTenantName());
        tenant.setType(dto.getType() != null ? dto.getType() : "enterprise");
        tenant.setIndustry(dto.getIndustry());
        tenant.setContactName(dto.getContactName());
        tenant.setContactPhone(dto.getContactPhone());
        tenant.setContactEmail(dto.getContactEmail());
        tenant.setExpireAt(dto.getExpireAt());
        tenant.setAttributes(dto.getAttributes());
        tenant.setStatus(1);
        return tenant;
    }

    private void validateConfigCreate(TenantConfigCreateDTO dto) {
        if (!StringUtils.hasText(dto.getConfigId())) {
            throw new ValidationException("配置ID不能为空");
        }
    }

    private String resolveNamespace(String namespace) {
        return namespace != null ? namespace : "default";
    }

    private TenantConfig buildTenantConfig(TenantConfigCreateDTO dto, Long tenantId, String namespace, Integer version) {
        TenantConfig config = new TenantConfig();
        config.setTenantId(tenantId);
        config.setConfigId(dto.getConfigId());
        config.setNamespace(namespace);
        config.setVersion(version);
        config.setParameters(dto.getParameters());
        config.setEnabled(dto.getEnabled() != null && dto.getEnabled() ? 1 : 0);
        config.setAppliedAt(dto.getAppliedAt() != null ? dto.getAppliedAt() : LocalDateTime.now());
        config.setDescription(dto.getDescription());
        return config;
    }

    private void validateQuotaCreate(TenantQuotaCreateDTO dto) {
        if (!StringUtils.hasText(dto.getResourceType())) {
            throw new ValidationException("资源类型不能为空");
        }
        if (dto.getQuotaLimit() == null || dto.getQuotaLimit() <= 0) {
            throw new ValidationException("配额限制必须大于0");
        }
    }

    private void checkQuotaUnique(Long tenantId, String resourceType) {
        TenantQuota exists = tenantQuotaMapper.selectOne(
                new LambdaQueryWrapper<TenantQuota>()
                        .eq(TenantQuota::getTenantId, tenantId)
                        .eq(TenantQuota::getResourceType, resourceType));
        if (exists != null) {
            throw new BusinessException(400, "该资源类型的配额已存在");
        }
    }

    private TenantQuota buildTenantQuota(TenantQuotaCreateDTO dto, Long tenantId) {
        TenantQuota quota = new TenantQuota();
        quota.setTenantId(tenantId);
        quota.setResourceType(dto.getResourceType());
        quota.setQuotaLimit(dto.getQuotaLimit());
        quota.setQuotaUsed(0L);
        quota.setUnit(dto.getUnit() != null ? dto.getUnit() : "count");
        quota.setResetPeriod(dto.getResetPeriod());
        quota.setWarningThreshold(dto.getWarningThreshold() != null ? dto.getWarningThreshold() : new BigDecimal("80.00"));
        quota.setAttributes(dto.getAttributes());
        return quota;
    }

    private TenantQuota findQuota(Long tenantId, String resourceType) {
        return tenantQuotaMapper.selectOne(
                new LambdaQueryWrapper<TenantQuota>()
                        .eq(TenantQuota::getTenantId, tenantId)
                        .eq(TenantQuota::getResourceType, resourceType));
    }

    private boolean tryAtomicConsume(TenantQuota quota, Long amount, Long tenantId, String resourceType) {
        int updated = tenantQuotaMapper.atomicConsume(quota.getId(), amount);
        if (updated == 0) {
            log.error("配额不足: tenantId={}, resourceType={}, used={}, limit={}, request={}",
                    tenantId, resourceType, quota.getQuotaUsed(), quota.getQuotaLimit(), amount);
            return false;
        }
        return true;
    }

    private void checkQuotaWarning(TenantQuota quota, Long tenantId, String resourceType) {
        TenantQuota updated = tenantQuotaMapper.selectById(quota.getId());
        if (updated == null) return;

        BigDecimal usagePercent = BigDecimal.valueOf(updated.getQuotaUsed())
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(updated.getQuotaLimit()), 2, RoundingMode.HALF_UP);

        if (usagePercent.compareTo(updated.getWarningThreshold()) >= 0) {
            log.warn("租户配额使用告警: tenantId={}, resourceType={}, usagePercent={}%, threshold={}%",
                    tenantId, resourceType, usagePercent, updated.getWarningThreshold());
        }
    }
}
