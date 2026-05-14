package com.deviceops.controller;

import com.deviceops.dto.ApiResponse;
import com.deviceops.entity.AlertRecord;
import com.deviceops.service.alert.AlertService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    @Autowired
    private AlertService alertService;

    @PostMapping
    public ApiResponse<AlertRecord> sendAlert(@RequestParam String deviceId,
                                               @RequestParam String alertType,
                                               @RequestParam String alertLevel) {
        AlertRecord alert = alertService.sendAlert(deviceId, alertType, alertLevel);
        return ApiResponse.success(alert);
    }

    @GetMapping("/{alertId}")
    public ApiResponse<AlertRecord> getAlert(@PathVariable String alertId) {
        AlertRecord alert = alertService.getAlert(alertId);
        return ApiResponse.success(alert);
    }

    @GetMapping
    public ApiResponse<List<AlertRecord>> getAllAlerts() {
        return ApiResponse.success(alertService.getAllAlerts());
    }

    @GetMapping("/device/{deviceId}")
    public ApiResponse<List<AlertRecord>> getAlertsByDevice(@PathVariable String deviceId) {
        return ApiResponse.success(alertService.getAlertsByDevice(deviceId));
    }

    @GetMapping("/unacknowledged")
    public ApiResponse<List<AlertRecord>> getUnacknowledgedAlerts() {
        return ApiResponse.success(alertService.getUnacknowledgedAlerts());
    }

    @GetMapping("/level/{level}")
    public ApiResponse<List<AlertRecord>> getAlertsByLevel(@PathVariable String level) {
        return ApiResponse.success(alertService.getAlertsByLevel(level));
    }

    @PutMapping("/{alertId}/acknowledge")
    public ApiResponse<AlertRecord> acknowledgeAlert(@PathVariable String alertId) {
        AlertRecord alert = alertService.acknowledgeAlert(alertId);
        return ApiResponse.success(alert);
    }
}
