package com.smartflow.multitenant.controller;

import com.smartflow.common.base.Result;
import com.smartflow.persistence.entity.Tenant;
import com.smartflow.persistence.entity.TenantConfig;
import com.smartflow.persistence.entity.TenantQuota;
import com.smartflow.multitenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenant")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @PostMapping
    public Result<Tenant> createTenant(@RequestBody Tenant tenant) {
        Tenant created = tenantService.createTenant(tenant);
        return Result.success(created);
    }

    @GetMapping("/{tenantId}")
    public Result<Tenant> getTenant(@PathVariable Long tenantId) {
        Tenant tenant = tenantService.getTenant(tenantId);
        return Result.success(tenant);
    }

    @GetMapping("/code/{tenantCode}")
    public Result<Tenant> getTenantByCode(@PathVariable String tenantCode) {
        Tenant tenant = tenantService.getTenantByCode(tenantCode);
        return Result.success(tenant);
    }

    @PutMapping
    public Result<Tenant> updateTenant(@RequestBody Tenant tenant) {
        Tenant updated = tenantService.updateTenant(tenant);
        return Result.success(updated);
    }

    @GetMapping
    public Result<List<Tenant>> listTenants() {
        List<Tenant> tenants = tenantService.listTenants();
        return Result.success(tenants);
    }

    @GetMapping("/{tenantId}/context")
    public Result<Map<String, Object>> getTenantContext(@PathVariable Long tenantId) {
        Map<String, Object> context = tenantService.getTenantContext(tenantId);
        return Result.success(context);
    }

    @PostMapping("/{tenantId}/config")
    public Result<TenantConfig> setConfig(
            @PathVariable Long tenantId,
            @RequestParam String configKey,
            @RequestParam String configValue,
            @RequestParam(defaultValue = "STRING") String configType) {
        TenantConfig config = tenantService.setConfig(tenantId, configKey, configValue, configType);
        return Result.success(config);
    }

    @GetMapping("/{tenantId}/config/{configKey}")
    public Result<Object> getConfig(@PathVariable Long tenantId, @PathVariable String configKey) {
        Object value = tenantService.getConfig(tenantId, configKey);
        return Result.success(value);
    }

    @PostMapping("/{tenantId}/quota")
    public Result<TenantQuota> setQuota(
            @PathVariable Long tenantId,
            @RequestParam String resourceType,
            @RequestParam Long quotaLimit) {
        TenantQuota quota = tenantService.setQuota(tenantId, resourceType, quotaLimit);
        return Result.success(quota);
    }

    @GetMapping("/{tenantId}/quota/check")
    public Result<Boolean> checkQuota(
            @PathVariable Long tenantId,
            @RequestParam String resourceType,
            @RequestParam Long requestedAmount) {
        boolean allowed = tenantService.checkQuota(tenantId, resourceType, requestedAmount);
        return Result.success(allowed);
    }
}
