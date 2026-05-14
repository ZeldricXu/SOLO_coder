package com.assetmanage.controller;

import com.assetmanage.dto.ApiResponse;
import com.assetmanage.dto.MaintenancePlanRequest;
import com.assetmanage.entity.MaintenanceRecord;
import com.assetmanage.service.MaintenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    @PostMapping
    public ApiResponse<String> createMaintenance(@RequestBody MaintenancePlanRequest request) {
        String maintId = maintenanceService.createMaintenance(request);
        return ApiResponse.success(maintId);
    }

    @GetMapping("/asset/{assetId}")
    public ApiResponse<List<MaintenanceRecord>> getMaintenanceByAsset(@PathVariable String assetId) {
        List<MaintenanceRecord> records = maintenanceService.getMaintenanceByAsset(assetId);
        return ApiResponse.success(records);
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<MaintenanceRecord>> getMaintenanceByType(@PathVariable String type) {
        List<MaintenanceRecord> records = maintenanceService.getMaintenanceByType(type);
        return ApiResponse.success(records);
    }

    @GetMapping("/upcoming")
    public ApiResponse<List<MaintenanceRecord>> getUpcomingMaintenance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        List<MaintenanceRecord> records = maintenanceService.getUpcomingMaintenance(start, end);
        return ApiResponse.success(records);
    }

    @GetMapping("/{maintId}")
    public ApiResponse<MaintenanceRecord> getMaintenanceById(@PathVariable String maintId) {
        MaintenanceRecord record = maintenanceService.getMaintenanceById(maintId);
        return ApiResponse.success(record);
    }

    @GetMapping
    public ApiResponse<List<MaintenanceRecord>> getAllMaintenance() {
        List<MaintenanceRecord> records = maintenanceService.getAllMaintenance();
        return ApiResponse.success(records);
    }
}
