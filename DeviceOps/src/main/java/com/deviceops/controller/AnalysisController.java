package com.deviceops.controller;

import com.deviceops.dto.ApiResponse;
import com.deviceops.entity.DeviceStatistics;
import com.deviceops.service.analysis.AnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    @Autowired
    private AnalysisService analysisService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> getOverviewStatistics() {
        Map<String, Object> stats = analysisService.getOverviewStatistics();
        return ApiResponse.success(stats);
    }

    @GetMapping("/monthly/{statMonth}")
    public ApiResponse<DeviceStatistics> getMonthlyStatistics(@PathVariable String statMonth) {
        DeviceStatistics stats = analysisService.getMonthlyStatistics(statMonth);
        return ApiResponse.success(stats);
    }

    @GetMapping("/monthly")
    public ApiResponse<List<DeviceStatistics>> getAllStatistics() {
        return ApiResponse.success(analysisService.getAllStatistics());
    }

    @GetMapping("/device-distribution")
    public ApiResponse<Map<String, Object>> getDeviceStatusDistribution() {
        Map<String, Object> distribution = analysisService.getDeviceStatusDistribution();
        return ApiResponse.success(distribution);
    }

    @GetMapping("/fault-distribution")
    public ApiResponse<Map<String, Object>> getFaultStatusDistribution() {
        Map<String, Object> distribution = analysisService.getFaultStatusDistribution();
        return ApiResponse.success(distribution);
    }

    @GetMapping("/task-distribution")
    public ApiResponse<Map<String, Object>> getTaskStatusDistribution() {
        Map<String, Object> distribution = analysisService.getTaskStatusDistribution();
        return ApiResponse.success(distribution);
    }

    @PostMapping("/refresh")
    public ApiResponse<Void> refreshStatistics() {
        analysisService.updateStatistics();
        return ApiResponse.success(null);
    }
}
