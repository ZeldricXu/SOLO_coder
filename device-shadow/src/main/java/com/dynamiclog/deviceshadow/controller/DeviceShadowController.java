package com.dynamiclog.deviceshadow.controller;

import com.dynamiclog.common.dto.ApiResponse;
import com.dynamiclog.common.entity.DeviceShadow;
import com.dynamiclog.deviceshadow.service.DeviceShadowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceShadowController {

    private final DeviceShadowService shadowService;

    @PostMapping
    public Mono<ApiResponse<DeviceShadow>> createShadow(@RequestBody DeviceShadow shadow) {
        return shadowService.createShadow(shadow)
                .map(ApiResponse::success);
    }

    @GetMapping("/{deviceId}/shadow")
    public Mono<ApiResponse<DeviceShadow>> getShadow(@PathVariable String deviceId) {
        return shadowService.getShadow(deviceId)
                .map(ApiResponse::success);
    }

    @PutMapping("/{deviceId}/shadow/desired")
    public Mono<ApiResponse<DeviceShadow>> updateDesiredState(
            @PathVariable String deviceId,
            @RequestBody Map<String, Object> desiredState) {
        return shadowService.updateDesiredState(deviceId, desiredState)
                .map(ApiResponse::success);
    }

    @PutMapping("/{deviceId}/shadow/reported")
    public Mono<ApiResponse<DeviceShadow>> updateReportedState(
            @PathVariable String deviceId,
            @RequestBody Map<String, Object> reportedState) {
        return shadowService.updateReportedState(deviceId, reportedState)
                .map(ApiResponse::success);
    }

    @PatchMapping("/{deviceId}/shadow/desired")
    public Mono<ApiResponse<DeviceShadow>> patchDesiredState(
            @PathVariable String deviceId,
            @RequestBody Map<String, Object> patch) {
        return shadowService.patchDesiredState(deviceId, patch)
                .map(ApiResponse::success);
    }

    @PatchMapping("/{deviceId}/shadow/reported")
    public Mono<ApiResponse<DeviceShadow>> patchReportedState(
            @PathVariable String deviceId,
            @RequestBody Map<String, Object> patch) {
        return shadowService.patchReportedState(deviceId, patch)
                .map(ApiResponse::success);
    }

    @GetMapping("/{deviceId}/shadow/delta")
    public Mono<ApiResponse<Map<String, Object>>> getDelta(@PathVariable String deviceId) {
        return shadowService.getDelta(deviceId)
                .map(ApiResponse::success);
    }

    @PostMapping("/{deviceId}/offline")
    public Mono<ApiResponse<Void>> markOffline(@PathVariable String deviceId) {
        return shadowService.markOffline(deviceId)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @DeleteMapping("/{deviceId}")
    public Mono<ApiResponse<Void>> deleteShadow(@PathVariable String deviceId) {
        return shadowService.deleteShadow(deviceId)
                .then(Mono.just(ApiResponse.success(null)));
    }
}
