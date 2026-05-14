package com.meeting.controller;

import com.meeting.dto.ApiResponse;
import com.meeting.entity.Device;
import com.meeting.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Device>>> getAllDevices() {
        List<Device> devices = deviceService.getAllDevices();
        return ResponseEntity.ok(ApiResponse.success(devices));
    }

    @GetMapping("/{deviceId}")
    public ResponseEntity<ApiResponse<Device>> getDeviceById(@PathVariable String deviceId) {
        Device device = deviceService.getDeviceById(deviceId);
        return ResponseEntity.ok(ApiResponse.success(device));
    }

    @GetMapping("/room/{roomId}")
    public ResponseEntity<ApiResponse<List<Device>>> getDevicesByRoom(@PathVariable String roomId) {
        List<Device> devices = deviceService.getDevicesByRoomId(roomId);
        return ResponseEntity.ok(ApiResponse.success(devices));
    }

    @GetMapping("/room/{roomId}/available")
    public ResponseEntity<ApiResponse<List<Device>>> getAvailableDevicesByRoom(@PathVariable String roomId) {
        List<Device> devices = deviceService.getAvailableDevicesByRoomId(roomId);
        return ResponseEntity.ok(ApiResponse.success(devices));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<Device>>> getDevicesByStatus(@PathVariable String status) {
        List<Device> devices = deviceService.getDevicesByStatus(status);
        return ResponseEntity.ok(ApiResponse.success(devices));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Device>> createDevice(@Valid @RequestBody Device device) {
        Device created = deviceService.createDevice(device);
        return ResponseEntity.ok(ApiResponse.success("设备创建成功", created));
    }

    @PutMapping("/{deviceId}")
    public ResponseEntity<ApiResponse<Device>> updateDevice(
            @PathVariable String deviceId,
            @RequestBody Device deviceUpdate) {
        Device updated = deviceService.updateDevice(deviceId, deviceUpdate);
        return ResponseEntity.ok(ApiResponse.success("设备更新成功", updated));
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<ApiResponse<Void>> deleteDevice(@PathVariable String deviceId) {
        deviceService.deleteDevice(deviceId);
        return ResponseEntity.ok(ApiResponse.success("设备删除成功", null));
    }

    @PutMapping("/{deviceId}/status")
    public ResponseEntity<ApiResponse<Device>> updateDeviceStatus(
            @PathVariable String deviceId,
            @RequestParam String status) {
        Device device = deviceService.updateDeviceStatus(deviceId, status);
        return ResponseEntity.ok(ApiResponse.success("设备状态更新成功", device));
    }

    @PostMapping("/{deviceId}/maintenance")
    public ResponseEntity<ApiResponse<Device>> markForMaintenance(@PathVariable String deviceId) {
        Device device = deviceService.markDeviceForMaintenance(deviceId);
        return ResponseEntity.ok(ApiResponse.success("设备已标记为维护", device));
    }

    @PostMapping("/{deviceId}/maintenance/complete")
    public ResponseEntity<ApiResponse<Device>> completeMaintenance(@PathVariable String deviceId) {
        Device device = deviceService.completeDeviceMaintenance(deviceId);
        return ResponseEntity.ok(ApiResponse.success("设备维护完成", device));
    }

    @GetMapping("/room/{roomId}/count")
    public ResponseEntity<ApiResponse<Map<String, Object>>> countAvailableDevices(@PathVariable String roomId) {
        long count = deviceService.countAvailableDevices(roomId);

        Map<String, Object> result = new HashMap<>();
        result.put("room_id", roomId);
        result.put("available_count", count);

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
