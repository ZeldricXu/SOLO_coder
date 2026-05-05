package com.iotconnect.controller;

import com.iotconnect.dto.ApiResponse;
import com.iotconnect.dto.DeviceRegisterRequest;
import com.iotconnect.dto.DeviceRegisterResponse;
import com.iotconnect.entity.Device;
import com.iotconnect.service.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceController.class);

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<DeviceRegisterResponse>> registerDevice(
            @Valid @RequestBody DeviceRegisterRequest request) {
        
        logger.info("Device registration request: deviceName={}, deviceType={}", 
                request.getDeviceName(), request.getDeviceType());
        
        try {
            DeviceRegisterResponse response = deviceService.registerDevice(request);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("Device registration failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Device registration failed: " + e.getMessage()));
        }
    }

    @GetMapping("/{deviceId}")
    public ResponseEntity<ApiResponse<Device>> getDevice(@PathVariable String deviceId) {
        logger.debug("Get device request: deviceId={}", deviceId);
        
        try {
            Device device = deviceService.getDevice(deviceId);
            return ResponseEntity.ok(ApiResponse.success(device));
        } catch (Exception e) {
            logger.error("Get device failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "Device not found: " + deviceId));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Device>>> getAllDevices() {
        logger.debug("Get all devices request");
        
        try {
            List<Device> devices = deviceService.getAllDevices();
            return ResponseEntity.ok(ApiResponse.success(devices));
        } catch (Exception e) {
            logger.error("Get all devices failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get devices: " + e.getMessage()));
        }
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<Device>>> getDevicesByStatus(@PathVariable String status) {
        logger.debug("Get devices by status: status={}", status);
        
        try {
            List<Device> devices = deviceService.getDevicesByStatus(status);
            return ResponseEntity.ok(ApiResponse.success(devices));
        } catch (Exception e) {
            logger.error("Get devices by status failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get devices: " + e.getMessage()));
        }
    }

    @GetMapping("/type/{deviceType}")
    public ResponseEntity<ApiResponse<List<Device>>> getDevicesByType(@PathVariable String deviceType) {
        logger.debug("Get devices by type: deviceType={}", deviceType);
        
        try {
            List<Device> devices = deviceService.getDevicesByType(deviceType);
            return ResponseEntity.ok(ApiResponse.success(devices));
        } catch (Exception e) {
            logger.error("Get devices by type failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get devices: " + e.getMessage()));
        }
    }

    @GetMapping("/group/{deviceGroup}")
    public ResponseEntity<ApiResponse<List<Device>>> getDevicesByGroup(@PathVariable String deviceGroup) {
        logger.debug("Get devices by group: deviceGroup={}", deviceGroup);
        
        try {
            List<Device> devices = deviceService.getDevicesByGroup(deviceGroup);
            return ResponseEntity.ok(ApiResponse.success(devices));
        } catch (Exception e) {
            logger.error("Get devices by group failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get devices: " + e.getMessage()));
        }
    }

    @PutMapping("/{deviceId}")
    public ResponseEntity<ApiResponse<Device>> updateDevice(
            @PathVariable String deviceId,
            @Valid @RequestBody DeviceRegisterRequest request) {
        
        logger.info("Update device request: deviceId={}", deviceId);
        
        try {
            Device updatedDevice = deviceService.updateDevice(deviceId, request);
            return ResponseEntity.ok(ApiResponse.success(updatedDevice));
        } catch (Exception e) {
            logger.error("Update device failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Update device failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<ApiResponse<Void>> deleteDevice(@PathVariable String deviceId) {
        logger.info("Delete device request: deviceId={}", deviceId);
        
        try {
            deviceService.deleteDevice(deviceId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            logger.error("Delete device failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Delete device failed: " + e.getMessage()));
        }
    }

    @GetMapping("/groups")
    public ResponseEntity<ApiResponse<List<String>>> getAllDeviceGroups() {
        logger.debug("Get all device groups request");
        
        try {
            List<String> groups = deviceService.getAllDeviceGroups();
            return ResponseEntity.ok(ApiResponse.success(groups));
        } catch (Exception e) {
            logger.error("Get device groups failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get device groups: " + e.getMessage()));
        }
    }

    @GetMapping("/statistics/count")
    public ResponseEntity<ApiResponse<Long>> getDeviceCount() {
        logger.debug("Get device count request");
        
        try {
            long count = deviceService.getDeviceCount();
            return ResponseEntity.ok(ApiResponse.success(count));
        } catch (Exception e) {
            logger.error("Get device count failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get device count: " + e.getMessage()));
        }
    }

    @GetMapping("/statistics/online")
    public ResponseEntity<ApiResponse<Long>> getOnlineDeviceCount() {
        logger.debug("Get online device count request");
        
        try {
            long count = deviceService.getOnlineDeviceCount();
            return ResponseEntity.ok(ApiResponse.success(count));
        } catch (Exception e) {
            logger.error("Get online device count failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get online device count: " + e.getMessage()));
        }
    }
}
