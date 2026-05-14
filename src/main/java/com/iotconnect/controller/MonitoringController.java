package com.iotconnect.controller;

import com.iotconnect.dto.ApiResponse;
import com.iotconnect.dto.DeviceDetailStatus;
import com.iotconnect.dto.DeviceStatusStatistics;
import com.iotconnect.dto.GroupStatusStatistics;
import com.iotconnect.dto.SystemOverview;
import com.iotconnect.service.MonitoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/monitoring")
public class MonitoringController {

    private static final Logger logger = LoggerFactory.getLogger(MonitoringController.class);

    private final MonitoringService monitoringService;

    public MonitoringController(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<SystemOverview>> getSystemOverview() {
        logger.debug("Get system overview request");
        
        try {
            SystemOverview overview = monitoringService.getSystemOverview();
            return ResponseEntity.ok(ApiResponse.success(overview));
        } catch (Exception e) {
            logger.error("Get system overview failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get system overview: " + e.getMessage()));
        }
    }

    @GetMapping("/statistics")
    public ResponseEntity<ApiResponse<DeviceStatusStatistics>> getDeviceStatistics() {
        logger.debug("Get device statistics request");
        
        try {
            DeviceStatusStatistics stats = monitoringService.getDeviceStatusStatistics();
            return ResponseEntity.ok(ApiResponse.success(stats));
        } catch (Exception e) {
            logger.error("Get device statistics failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get device statistics: " + e.getMessage()));
        }
    }

    @GetMapping("/device/{deviceId}/status")
    public ResponseEntity<ApiResponse<DeviceDetailStatus>> getDeviceDetailStatus(
            @PathVariable String deviceId) {
        
        logger.debug("Get device detail status: deviceId={}", deviceId);
        
        try {
            DeviceDetailStatus status = monitoringService.getDeviceDetailStatus(deviceId);
            return ResponseEntity.ok(ApiResponse.success(status));
        } catch (Exception e) {
            logger.error("Get device detail status failed: {}", e.getMessage());
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, "Device not found: " + deviceId));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get device status: " + e.getMessage()));
        }
    }

    @GetMapping("/group/{deviceGroup}/status")
    public ResponseEntity<ApiResponse<GroupStatusStatistics>> getGroupStatus(
            @PathVariable String deviceGroup) {
        
        logger.debug("Get group status: deviceGroup={}", deviceGroup);
        
        try {
            GroupStatusStatistics stats = monitoringService.getGroupStatusStatistics(deviceGroup);
            return ResponseEntity.ok(ApiResponse.success(stats));
        } catch (Exception e) {
            logger.error("Get group status failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get group status: " + e.getMessage()));
        }
    }

    @GetMapping("/device-types/distribution")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getDeviceTypeDistribution() {
        logger.debug("Get device type distribution request");
        
        try {
            Map<String, Long> distribution = monitoringService.getDeviceTypeDistribution();
            return ResponseEntity.ok(ApiResponse.success(distribution));
        } catch (Exception e) {
            logger.error("Get device type distribution failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get device type distribution: " + e.getMessage()));
        }
    }
}
