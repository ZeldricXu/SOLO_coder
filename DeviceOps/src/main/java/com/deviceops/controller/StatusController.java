package com.deviceops.controller;

import com.deviceops.dto.ApiResponse;
import com.deviceops.entity.StatusRecord;
import com.deviceops.service.alert.AlertService;
import com.deviceops.service.history.HistoryService;
import com.deviceops.service.monitor.MonitorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    @Autowired
    private MonitorService monitorService;

    @Autowired
    private AlertService alertService;

    @Autowired
    private HistoryService historyService;

    @GetMapping("/query")
    public ApiResponse<Map<String, Object>> queryStatus(@RequestParam String deviceId) {
        Map<String, Integer> status = monitorService.getLatestStatus(deviceId);
        String overallStatus = monitorService.determineDeviceStatus(deviceId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("overall_status", overallStatus);
        
        return ApiResponse.success(result);
    }

    @GetMapping("/{deviceId}")
    public ApiResponse<Map<String, Integer>> getLatestStatus(@PathVariable String deviceId) {
        Map<String, Integer> status = monitorService.getLatestStatus(deviceId);
        return ApiResponse.success(status);
    }

    @GetMapping("/{deviceId}/history")
    public ApiResponse<List<StatusRecord>> getStatusHistory(@PathVariable String deviceId) {
        List<StatusRecord> history = monitorService.getStatusHistory(deviceId);
        return ApiResponse.success(history);
    }

    @PostMapping("/{deviceId}/collect")
    public ApiResponse<Map<String, StatusRecord>> collectStatus(@PathVariable String deviceId) {
        Map<String, StatusRecord> records = monitorService.collectAllStatus(deviceId);
        
        String status = monitorService.determineDeviceStatus(deviceId);
        historyService.recordMonitor(deviceId, status);
        
        if ("abnormal".equals(status) || "warning".equals(status)) {
            alertService.checkAndSendAlert(deviceId, status);
        }
        
        return ApiResponse.success(records);
    }

    @PostMapping("/{deviceId}/collect/{statusType}")
    public ApiResponse<StatusRecord> collectSpecificStatus(@PathVariable String deviceId,
                                                           @PathVariable String statusType) {
        StatusRecord record = monitorService.collectStatus(deviceId, statusType);
        return ApiResponse.success(record);
    }

    @GetMapping("/{deviceId}/check")
    public ApiResponse<Map<String, Object>> checkDeviceStatus(@PathVariable String deviceId) {
        String status = monitorService.determineDeviceStatus(deviceId);
        boolean hasAbnormal = monitorService.hasAbnormalStatus(deviceId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("deviceId", deviceId);
        result.put("status", status);
        result.put("hasAbnormal", hasAbnormal);
        
        return ApiResponse.success(result);
    }
}
