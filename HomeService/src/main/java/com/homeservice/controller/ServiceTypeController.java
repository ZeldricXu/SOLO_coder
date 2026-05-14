package com.homeservice.controller;

import com.homeservice.dto.ApiResponse;
import com.homeservice.dto.ServiceTypeRequest;
import com.homeservice.entity.ServiceType;
import com.homeservice.service.ServiceTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/service-types")
public class ServiceTypeController {

    @Autowired
    private ServiceTypeService serviceTypeService;

    @PostMapping
    public ApiResponse<ServiceType> createServiceType(@RequestBody ServiceTypeRequest request) {
        ServiceType created = serviceTypeService.createServiceType(request);
        return ApiResponse.success(created);
    }

    @GetMapping
    public ApiResponse<List<ServiceType>> getAllServiceTypes() {
        List<ServiceType> types = serviceTypeService.getAllServiceTypes();
        return ApiResponse.success(types);
    }

    @GetMapping("/active")
    public ApiResponse<List<ServiceType>> getActiveServiceTypes() {
        List<ServiceType> types = serviceTypeService.getActiveServiceTypes();
        return ApiResponse.success(types);
    }

    @GetMapping("/{typeCode}")
    public ApiResponse<ServiceType> getServiceType(@PathVariable String typeCode) {
        ServiceType type = serviceTypeService.getServiceTypeByCode(typeCode);
        return ApiResponse.success(type);
    }

    @PutMapping("/{typeCode}")
    public ApiResponse<ServiceType> updateServiceType(@PathVariable String typeCode, @RequestBody ServiceTypeRequest request) {
        ServiceType updated = serviceTypeService.updateServiceType(typeCode, request);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{typeCode}")
    public ApiResponse<Void> deleteServiceType(@PathVariable String typeCode) {
        serviceTypeService.deleteServiceType(typeCode);
        return ApiResponse.success(null);
    }

    @PostMapping("/{typeCode}/activate")
    public ApiResponse<ServiceType> activateServiceType(@PathVariable String typeCode) {
        ServiceType activated = serviceTypeService.activateServiceType(typeCode);
        return ApiResponse.success(activated);
    }

    @PostMapping("/{typeCode}/deactivate")
    public ApiResponse<ServiceType> deactivateServiceType(@PathVariable String typeCode) {
        ServiceType deactivated = serviceTypeService.deactivateServiceType(typeCode);
        return ApiResponse.success(deactivated);
    }
}
