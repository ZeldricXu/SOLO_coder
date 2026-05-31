package com.orchestration.tenant.controller;

import com.orchestration.common.api.ApiConstants;
import com.orchestration.common.base.Result;
import com.orchestration.persistence.entity.Tenant;
import com.orchestration.persistence.entity.TenantConfig;
import com.orchestration.persistence.entity.TenantResourceQuota;
import com.orchestration.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.API_V1_PREFIX + "/tenant")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @PostMapping("/tenants")
    public Result<Long> createTenant(@RequestBody Tenant tenant) {
        return Result.success(tenantService.createTenant(tenant));
    }

    @PutMapping("/tenants/{id}")
    public Result<Boolean> updateTenant(@PathVariable Long id, @RequestBody Tenant tenant) {
        tenant.setId(id);
        return Result.success(tenantService.updateTenant(tenant));
    }

    @GetMapping("/tenants/{id}")
    public Result<Tenant> getTenant(@PathVariable Long id) {
        return Result.success(tenantService.getTenant(id));
    }

    @GetMapping("/tenants/code/{tenantCode}")
    public Result<Tenant> getTenantByCode(@PathVariable String tenantCode) {
        return Result.success(tenantService.getTenantByCode(tenantCode));
    }

    @GetMapping("/tenants")
    public Result<List<Tenant>> listTenants(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(tenantService.listTenants(page, size));
    }

    @PostMapping("/tenants/{id}/enable")
    public Result<Boolean> enableTenant(@PathVariable Long id) {
        return Result.success(tenantService.enableTenant(id));
    }

    @PostMapping("/tenants/{id}/disable")
    public Result<Boolean> disableTenant(@PathVariable Long id) {
        return Result.success(tenantService.disableTenant(id));
    }

    @PostMapping("/{tenantId}/configs")
    public Result<Long> setConfig(
            @PathVariable Long tenantId,
            @RequestParam String configKey,
            @RequestParam String configValue,
            @RequestParam(required = false) String configType,
            @RequestParam(required = false) String description) {
        return Result.success(tenantService.setConfig(tenantId, configKey, configValue, configType, description));
    }

    @GetMapping("/{tenantId}/configs")
    public Result<List<TenantConfig>> listConfigs(@PathVariable Long tenantId) {
        return Result.success(tenantService.listConfigs(tenantId));
    }

    @GetMapping("/{tenantId}/configs/{configKey}")
    public Result<String> getConfigValue(
            @PathVariable Long tenantId,
            @PathVariable String configKey) {
        return Result.success(tenantService.getConfigValue(tenantId, configKey));
    }

    @DeleteMapping("/configs/{id}")
    public Result<Boolean> deleteConfig(@PathVariable Long id) {
        return Result.success(tenantService.deleteConfig(id));
    }

    @PostMapping("/{tenantId}/quotas")
    public Result<Long> setQuota(
            @PathVariable Long tenantId,
            @RequestParam String resourceType,
            @RequestParam Long quotaLimit,
            @RequestParam(required = false) String unit) {
        return Result.success(tenantService.setQuota(tenantId, resourceType, quotaLimit, unit));
    }

    @GetMapping("/{tenantId}/quotas")
    public Result<List<TenantResourceQuota>> listQuotas(@PathVariable Long tenantId) {
        return Result.success(tenantService.listQuotas(tenantId));
    }

    @PostMapping("/{tenantId}/quotas/consume")
    public Result<Boolean> consumeQuota(
            @PathVariable Long tenantId,
            @RequestParam String resourceType,
            @RequestParam Long amount) {
        return Result.success(tenantService.consumeQuota(tenantId, resourceType, amount));
    }

    @PostMapping("/{tenantId}/quotas/release")
    public Result<Boolean> releaseQuota(
            @PathVariable Long tenantId,
            @RequestParam String resourceType,
            @RequestParam Long amount) {
        return Result.success(tenantService.releaseQuota(tenantId, resourceType, amount));
    }

    @GetMapping("/{tenantId}/usage")
    public Result<Map<String, Object>> getTenantUsage(@PathVariable Long tenantId) {
        return Result.success(tenantService.getTenantUsage(tenantId));
    }

    @GetMapping("/{tenantId}/quotas/warning")
    public Result<Boolean> checkQuotaWarning(@PathVariable Long tenantId) {
        return Result.success(tenantService.checkQuotaWarning(tenantId));
    }
}
