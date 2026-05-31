package com.edgescheduler.shadow.controller;

import com.edgescheduler.common.dto.ApiResponse;
import com.edgescheduler.shadow.dto.DeviceShadowDTO;
import com.edgescheduler.shadow.entity.ShadowOperationLog;
import com.edgescheduler.shadow.service.DeviceShadowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/shadow")
@RequiredArgsConstructor
public class DeviceShadowController {

    private final DeviceShadowService shadowService;

    @PostMapping("/{deviceKey}")
    public Mono<ApiResponse<DeviceShadowDTO>> createShadow(@PathVariable String deviceKey) {
        return Mono.just(ApiResponse.created(shadowService.createShadow(deviceKey)));
    }

    @GetMapping("/{deviceKey}")
    public Mono<ApiResponse<DeviceShadowDTO>> getShadow(@PathVariable String deviceKey) {
        return Mono.just(ApiResponse.success(shadowService.getShadow(deviceKey)));
    }

    @GetMapping("/{deviceKey}/status")
    public Mono<ApiResponse<DeviceShadowDTO>> getShadowStatus(@PathVariable String deviceKey) {
        return Mono.just(ApiResponse.success(shadowService.getShadowStatus(deviceKey)));
    }

    @PutMapping("/{deviceKey}/desired")
    public Mono<ApiResponse<DeviceShadowDTO>> updateDesired(
            @PathVariable String deviceKey,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> desired = (Map<String, Object>) body.get("desired");
        String operator = (String) body.get("operator");
        return Mono.just(ApiResponse.success(shadowService.updateDesired(deviceKey, desired, operator)));
    }

    @PutMapping("/{deviceKey}/reported")
    public Mono<ApiResponse<DeviceShadowDTO>> updateReported(
            @PathVariable String deviceKey,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> reported = (Map<String, Object>) body.get("reported");
        String operator = (String) body.get("operator");
        return Mono.just(ApiResponse.success(shadowService.updateReported(deviceKey, reported, operator)));
    }

    @PutMapping("/{deviceKey}/merge")
    public Mono<ApiResponse<DeviceShadowDTO>> mergeShadow(
            @PathVariable String deviceKey,
            @RequestBody Map<String, Object> body) {
        String operator = (String) body.get("operator");
        body.remove("operator");
        return Mono.just(ApiResponse.success(shadowService.mergeShadow(deviceKey, body, operator)));
    }

    @PostMapping("/{deviceKey}/sync")
    public Mono<ApiResponse<DeviceShadowDTO>> syncShadow(@PathVariable String deviceKey) {
        return Mono.just(ApiResponse.success(shadowService.syncShadow(deviceKey)));
    }

    @DeleteMapping("/{deviceKey}")
    public Mono<ApiResponse<Void>> deleteShadow(@PathVariable String deviceKey) {
        shadowService.deleteShadow(deviceKey);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/delta")
    public Mono<ApiResponse<Map<String, Object>>> calculateDelta(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> desired = (Map<String, Object>) body.get("desired");
        @SuppressWarnings("unchecked")
        Map<String, Object> reported = (Map<String, Object>) body.get("reported");
        return Mono.just(ApiResponse.success(shadowService.calculateDelta(desired, reported)));
    }

    @GetMapping("/{deviceKey}/logs")
    public Mono<ApiResponse<List<ShadowOperationLog>>> getOperationLogs(
            @PathVariable String deviceKey,
            @RequestParam(defaultValue = "20") int limit) {
        return Mono.just(ApiResponse.success(shadowService.getOperationLogs(deviceKey, limit)));
    }
}
