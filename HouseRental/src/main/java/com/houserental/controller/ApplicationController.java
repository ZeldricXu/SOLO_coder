package com.houserental.controller;

import com.houserental.dto.ApiResponse;
import com.houserental.dto.ApplicationApproveDTO;
import com.houserental.dto.ApplicationCreateDTO;
import com.houserental.dto.ApplicationRejectDTO;
import com.houserental.entity.LeaseApplication;
import com.houserental.service.ApplicationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

    @Autowired
    private ApplicationService applicationService;

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createApplication(@Valid @RequestBody ApplicationCreateDTO dto) {
        LeaseApplication application = applicationService.createApplication(dto);

        Map<String, Object> result = new HashMap<>();
        result.put("application_id", application.getApplicationId());
        result.put("house_id", application.getHouseId());
        result.put("tenant_id", application.getTenantId());
        result.put("status", application.getApplicationStatus());
        result.put("application_time", application.getApplicationTime());

        return ApiResponse.success(result);
    }

    @GetMapping("/{applicationId}")
    public ApiResponse<LeaseApplication> getApplicationById(@PathVariable String applicationId) {
        LeaseApplication application = applicationService.getApplicationById(applicationId);
        return ApiResponse.success(application);
    }

    @PostMapping("/approve")
    public ApiResponse<LeaseApplication> approveApplication(@Valid @RequestBody ApplicationApproveDTO dto) {
        LeaseApplication application = applicationService.approveApplication(dto);
        return ApiResponse.success(application);
    }

    @PostMapping("/reject")
    public ApiResponse<LeaseApplication> rejectApplication(@Valid @RequestBody ApplicationRejectDTO dto) {
        LeaseApplication application = applicationService.rejectApplication(dto);
        return ApiResponse.success(application);
    }

    @PostMapping("/{applicationId}/cancel")
    public ApiResponse<LeaseApplication> cancelApplication(@PathVariable String applicationId) {
        LeaseApplication application = applicationService.cancelApplication(applicationId);
        return ApiResponse.success(application);
    }

    @GetMapping("/list")
    public ApiResponse<List<LeaseApplication>> getAllApplications() {
        List<LeaseApplication> applications = applicationService.getAllApplications();
        return ApiResponse.success(applications);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<LeaseApplication>> getApplicationsByStatus(@PathVariable String status) {
        List<LeaseApplication> applications = applicationService.getApplicationsByStatus(status);
        return ApiResponse.success(applications);
    }

    @GetMapping("/house/{houseId}")
    public ApiResponse<List<LeaseApplication>> getApplicationsByHouse(@PathVariable String houseId) {
        List<LeaseApplication> applications = applicationService.getApplicationsByHouse(houseId);
        return ApiResponse.success(applications);
    }

    @GetMapping("/tenant/{tenantId}")
    public ApiResponse<List<LeaseApplication>> getApplicationsByTenant(@PathVariable String tenantId) {
        List<LeaseApplication> applications = applicationService.getApplicationsByTenant(tenantId);
        return ApiResponse.success(applications);
    }

    @GetMapping("/landlord/{landlordId}")
    public ApiResponse<List<LeaseApplication>> getApplicationsByLandlord(@PathVariable String landlordId) {
        List<LeaseApplication> applications = applicationService.getApplicationsByLandlord(landlordId);
        return ApiResponse.success(applications);
    }

    @GetMapping("/landlord/{landlordId}/pending")
    public ApiResponse<List<LeaseApplication>> getPendingApplicationsByLandlord(@PathVariable String landlordId) {
        List<LeaseApplication> applications = applicationService.getPendingApplicationsByLandlord(landlordId);
        return ApiResponse.success(applications);
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> getApplicationStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", applicationService.countTotalApplications());
        stats.put("pending", applicationService.countPendingApplications());
        stats.put("approved", applicationService.countApprovedApplications());
        stats.put("rejected", applicationService.countRejectedApplications());
        return ApiResponse.success(stats);
    }
}
