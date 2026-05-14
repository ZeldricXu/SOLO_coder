package com.homeservice.controller;

import com.homeservice.dto.ApiResponse;
import com.homeservice.dto.ServiceRegionRequest;
import com.homeservice.entity.ServiceRegion;
import com.homeservice.service.ServiceRegionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/service-regions")
public class ServiceRegionController {

    @Autowired
    private ServiceRegionService serviceRegionService;

    @PostMapping
    public ApiResponse<ServiceRegion> createServiceRegion(@RequestBody ServiceRegionRequest request) {
        ServiceRegion created = serviceRegionService.createServiceRegion(request);
        return ApiResponse.success(created);
    }

    @GetMapping
    public ApiResponse<List<ServiceRegion>> getAllServiceRegions() {
        List<ServiceRegion> regions = serviceRegionService.getAllServiceRegions();
        return ApiResponse.success(regions);
    }

    @GetMapping("/active")
    public ApiResponse<List<ServiceRegion>> getActiveServiceRegions() {
        List<ServiceRegion> regions = serviceRegionService.getActiveServiceRegions();
        return ApiResponse.success(regions);
    }

    @GetMapping("/{regionCode}")
    public ApiResponse<ServiceRegion> getServiceRegion(@PathVariable String regionCode) {
        ServiceRegion region = serviceRegionService.getServiceRegionByCode(regionCode);
        return ApiResponse.success(region);
    }

    @PutMapping("/{regionCode}")
    public ApiResponse<ServiceRegion> updateServiceRegion(@PathVariable String regionCode, @RequestBody ServiceRegionRequest request) {
        ServiceRegion updated = serviceRegionService.updateServiceRegion(regionCode, request);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{regionCode}")
    public ApiResponse<Void> deleteServiceRegion(@PathVariable String regionCode) {
        serviceRegionService.deleteServiceRegion(regionCode);
        return ApiResponse.success(null);
    }

    @PostMapping("/{regionCode}/activate")
    public ApiResponse<ServiceRegion> activateServiceRegion(@PathVariable String regionCode) {
        ServiceRegion activated = serviceRegionService.activateServiceRegion(regionCode);
        return ApiResponse.success(activated);
    }

    @PostMapping("/{regionCode}/deactivate")
    public ApiResponse<ServiceRegion> deactivateServiceRegion(@PathVariable String regionCode) {
        ServiceRegion deactivated = serviceRegionService.deactivateServiceRegion(regionCode);
        return ApiResponse.success(deactivated);
    }
}
