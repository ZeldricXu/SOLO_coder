package com.deviceops.controller;

import com.deviceops.dto.ApiResponse;
import com.deviceops.dto.DeviceCreateRequest;
import com.deviceops.entity.Device;
import com.deviceops.service.device.DeviceService;
import com.deviceops.service.history.HistoryService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private HistoryService historyService;

    @PostMapping("/create")
    public ApiResponse<Map<String, String>> createDevice(@Valid @RequestBody DeviceCreateRequest request) {
        Device device = deviceService.createDevice(request);
        
        historyService.recordDeviceCreate(device.getDeviceId(), device.getDeviceName());
        
        Map<String, String> result = new HashMap<>();
        result.put("device_id", device.getDeviceId());
        result.put("status", device.getDeviceStatus());
        
        return ApiResponse.success(result);
    }

    @GetMapping("/{deviceId}")
    public ApiResponse<Device> getDevice(@PathVariable String deviceId) {
        Device device = deviceService.getDevice(deviceId);
        return ApiResponse.success(device);
    }

    @GetMapping
    public ApiResponse<List<Device>> getAllDevices() {
        return ApiResponse.success(deviceService.getAllDevices());
    }

    @GetMapping("/type/{deviceType}")
    public ApiResponse<List<Device>> getDevicesByType(@PathVariable String deviceType) {
        return ApiResponse.success(deviceService.getDevicesByType(deviceType));
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<Device>> getDevicesByStatus(@PathVariable String status) {
        return ApiResponse.success(deviceService.getDevicesByStatus(status));
    }

    @PutMapping("/{deviceId}")
    public ApiResponse<Device> updateDevice(@PathVariable String deviceId, 
                                            @Valid @RequestBody DeviceCreateRequest request) {
        Device device = deviceService.updateDevice(deviceId, request);
        return ApiResponse.success(device);
    }

    @PutMapping("/{deviceId}/status")
    public ApiResponse<Device> updateDeviceStatus(@PathVariable String deviceId, 
                                                  @RequestParam String status) {
        Device oldDevice = deviceService.getDevice(deviceId);
        String oldStatus = oldDevice.getDeviceStatus();
        
        Device device = deviceService.updateDeviceStatus(deviceId, status);
        
        historyService.recordDeviceStatusUpdate(deviceId, oldStatus, status);
        
        return ApiResponse.success(device);
    }

    @DeleteMapping("/{deviceId}")
    public ApiResponse<Void> deleteDevice(@PathVariable String deviceId) {
        deviceService.deleteDevice(deviceId);
        return ApiResponse.success(null);
    }

    @GetMapping("/count")
    public ApiResponse<Map<String, Long>> getDeviceCount() {
        Map<String, Long> count = new HashMap<>();
        count.put("total", deviceService.count());
        count.put("normal", deviceService.countByStatus("normal"));
        count.put("warning", deviceService.countByStatus("warning"));
        count.put("abnormal", deviceService.countByStatus("abnormal"));
        return ApiResponse.success(count);
    }
}
