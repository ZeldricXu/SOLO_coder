package com.contractai.tenant.controller;

import com.contractai.common.dto.PageQuery;
import com.contractai.common.dto.PageResult;
import com.contractai.common.result.ApiResponse;
import com.contractai.tenant.dto.QuotaUsageDTO;
import com.contractai.tenant.dto.TenantConfigCreateDTO;
import com.contractai.tenant.dto.TenantCreateDTO;
import com.contractai.tenant.dto.TenantQuotaCreateDTO;
import com.contractai.tenant.entity.Tenant;
import com.contractai.tenant.entity.TenantConfig;
import com.contractai.tenant.entity.TenantQuota;
import com.contractai.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @PostMapping
    public ApiResponse<Tenant> createTenant(@RequestBody TenantCreateDTO dto) {
        return ApiResponse.created(tenantService.createTenant(dto));
    }

    @GetMapping("/{id}")
    public ApiResponse<Tenant> getTenant(@PathVariable Long id) {
        return ApiResponse.success(tenantService.getTenant(id));
    }

    @GetMapping("/code/{code}")
    public ApiResponse<Tenant> getTenantByCode(@PathVariable String code) {
        return ApiResponse.success(tenantService.getTenantByCode(code));
    }

    @GetMapping
    public ApiResponse<PageResult<Tenant>> listTenants(@ModelAttribute PageQuery query) {
        return ApiResponse.success(tenantService.listTenants(query));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateTenantStatus(@PathVariable Long id, @RequestParam Integer status) {
        tenantService.updateTenantStatus(id, status);
        return ApiResponse.success();
    }

    @PostMapping("/configs")
    public ApiResponse<TenantConfig> createConfig(@RequestBody TenantConfigCreateDTO dto) {
        return ApiResponse.created(tenantService.createConfig(dto));
    }

    @GetMapping("/configs/{configId}")
    public ApiResponse<TenantConfig> getConfig(
            @RequestHeader("X-Tenant-Id") Long tenantId,
            @PathVariable String configId,
            @RequestParam(required = false, defaultValue = "default") String namespace) {
        return ApiResponse.success(tenantService.getConfig(tenantId, configId, namespace));
    }

    @GetMapping("/configs/{configId}/parameters")
    public ApiResponse<Map<String, Object>> getConfigParameters(
            @RequestHeader("X-Tenant-Id") Long tenantId,
            @PathVariable String configId,
            @RequestParam(required = false, defaultValue = "default") String namespace) {
        return ApiResponse.success(tenantService.getConfigParameters(tenantId, configId, namespace));
    }

    @PostMapping("/quotas")
    public ApiResponse<TenantQuota> createQuota(@RequestBody TenantQuotaCreateDTO dto) {
        return ApiResponse.created(tenantService.createQuota(dto));
    }

    @GetMapping("/quotas")
    public ApiResponse<List<TenantQuota>> listQuotas(@RequestHeader("X-Tenant-Id") Long tenantId) {
        return ApiResponse.success(tenantService.listQuotas(tenantId));
    }

    @PostMapping("/quotas/consume")
    public ApiResponse<Boolean> consumeQuota(@RequestBody QuotaUsageDTO dto) {
        boolean success = tenantService.consumeQuota(dto.getResourceType(), dto.getUsageAmount());
        if (!success) {
            return ApiResponse.error(429, "配额不足");
        }
        return ApiResponse.success(true);
    }
}
