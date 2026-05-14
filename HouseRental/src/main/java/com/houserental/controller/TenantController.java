package com.houserental.controller;

import com.houserental.dto.ApiResponse;
import com.houserental.dto.TenantDTO;
import com.houserental.entity.Tenant;
import com.houserental.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    @Autowired
    private TenantService tenantService;

    @PostMapping("/create")
    public ApiResponse<Tenant> createTenant(@Valid @RequestBody TenantDTO dto) {
        Tenant tenant = tenantService.createTenant(dto);
        return ApiResponse.success(tenant);
    }

    @GetMapping("/{tenantId}")
    public ApiResponse<Tenant> getTenantById(@PathVariable String tenantId) {
        Tenant tenant = tenantService.getTenantById(tenantId);
        return ApiResponse.success(tenant);
    }

    @PutMapping("/{tenantId}")
    public ApiResponse<Tenant> updateTenant(@PathVariable String tenantId, @RequestBody TenantDTO dto) {
        Tenant tenant = tenantService.updateTenant(tenantId, dto);
        return ApiResponse.success(tenant);
    }

    @GetMapping("/list")
    public ApiResponse<List<Tenant>> getAllTenants() {
        List<Tenant> tenants = tenantService.getAllTenants();
        return ApiResponse.success(tenants);
    }

    @GetMapping("/active")
    public ApiResponse<List<Tenant>> getActiveTenants() {
        List<Tenant> tenants = tenantService.getActiveTenants();
        return ApiResponse.success(tenants);
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> getTenantStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", tenantService.countTotalTenants());
        stats.put("active", tenantService.countActiveTenants());
        return ApiResponse.success(stats);
    }
}
