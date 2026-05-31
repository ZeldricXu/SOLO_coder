package com.edgescheduler.modules.device.controller;

import com.edgescheduler.common.Result;
import com.edgescheduler.modules.device.domain.DeviceInfo;
import com.edgescheduler.modules.device.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @PostMapping
    public Mono<Result<DeviceInfo>> registerDevice(@RequestBody Map<String, Object> request) {
        String deviceId = (String) request.get("deviceId");
        String deviceName = (String) request.getOrDefault("deviceName", deviceId);
        String deviceModel = (String) request.get("deviceModel");
        String firmwareVersion = (String) request.get("firmwareVersion");
        Map<String, Object> metadata = (Map<String, Object>) request.getOrDefault("metadata", Map.of());

        return deviceService.registerDevice(deviceId, deviceName, deviceModel, firmwareVersion, metadata)
                .map(Result::success);
    }

    @PostMapping("/{deviceId}/activate")
    public Mono<Result<DeviceInfo>> activateDevice(
            @PathVariable String deviceId,
            @RequestBody Map<String, Object> request) {
        String activationCode = (String) request.get("activationCode");
        Map<String, Object> activationParams = (Map<String, Object>) request.getOrDefault("activationParams", Map.of());
        return deviceService.activateDevice(deviceId, activationCode, activationParams)
                .map(Result::success);
    }

    @PostMapping("/authenticate")
    public Mono<Result<Map<String, Object>>> authenticateDevice(
            @RequestParam String deviceId,
            @RequestParam String deviceSecret) {
        return deviceService.authenticateDevice(deviceId, deviceSecret)
                .map(Result::success);
    }

    @PostMapping("/{deviceId}/heartbeat")
    public Mono<Result<DeviceInfo>> heartbeat(
            @PathVariable String deviceId,
            @RequestBody Map<String, Object> heartbeat) {
        Integer cpuUsage = (Integer) heartbeat.get("cpuUsage");
        Integer memoryUsage = (Integer) heartbeat.get("memoryUsage");
        Integer storageUsage = (Integer) heartbeat.get("storageUsage");
        Double temperature = heartbeat.get("temperature") != null ? ((Number) heartbeat.get("temperature")).doubleValue() : null;
        String networkStatus = (String) heartbeat.getOrDefault("networkStatus", "online");

        return deviceService.heartbeat(deviceId, cpuUsage, memoryUsage, storageUsage, temperature, networkStatus)
                .map(Result::success);
    }

    @GetMapping
    public Flux<Result<DeviceInfo>> getDevices(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String deviceModel) {
        return deviceService.getDevices(status, deviceModel)
                .map(Result::success);
    }

    @GetMapping("/{deviceId}")
    public Mono<Result<DeviceInfo>> getDevice(@PathVariable String deviceId) {
        return deviceService.getDevice(deviceId)
                .map(Result::success);
    }

    @PutMapping("/{deviceId}")
    public Mono<Result<DeviceInfo>> updateDevice(
            @PathVariable String deviceId,
            @RequestBody Map<String, Object> updates) {
        return deviceService.updateDevice(deviceId, updates)
                .map(Result::success);
    }

    @DeleteMapping("/{deviceId}")
    public Mono<Result<Void>> deactivateDevice(@PathVariable String deviceId) {
        return deviceService.deactivateDevice(deviceId)
                .then(Mono.just(Result.success()));
    }

    @PostMapping("/{deviceId}/reset-secret")
    public Mono<Result<Map<String, Object>>> resetDeviceSecret(@PathVariable String deviceId) {
        return deviceService.resetDeviceSecret(deviceId)
                .map(Result::success);
    }

    @GetMapping("/stats")
    public Mono<Result<Map<String, Object>>> getDeviceStats() {
        return deviceService.getDeviceStats()
                .map(Result::success);
    }

    @GetMapping("/offline-detection")
    public Mono<Result<Map<String, Object>>> detectOfflineDevices() {
        return deviceService.detectOfflineDevices()
                .map(Result::success);
    }
}
