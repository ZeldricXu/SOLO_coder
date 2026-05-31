package com.edgescheduler.modules.shadow.controller;

import com.edgescheduler.common.Result;
import com.edgescheduler.modules.shadow.domain.DeviceShadow;
import com.edgescheduler.modules.shadow.service.DeviceShadowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/shadow")
@RequiredArgsConstructor
public class DeviceShadowController {

    private final DeviceShadowService deviceShadowService;

    @GetMapping("/{deviceId}")
    public Mono<Result<DeviceShadow>> getShadow(@PathVariable String deviceId) {
        return deviceShadowService.getShadow(deviceId)
                .map(Result::success);
    }

    @PutMapping("/{deviceId}/desired")
    public Mono<Result<DeviceShadow>> updateDesiredState(
            @PathVariable String deviceId,
            @RequestBody Map<String, Object> desiredState,
            @RequestHeader("X-Signature") String signature,
            @RequestHeader("X-Timestamp") long timestamp) {
        return deviceShadowService.updateDesiredState(deviceId, desiredState, signature, timestamp)
                .map(Result::success);
    }

    @PutMapping("/{deviceId}/reported")
    public Mono<Result<DeviceShadow>> updateReportedState(
            @PathVariable String deviceId,
            @RequestBody Map<String, Object> reportedState) {
        return deviceShadowService.updateReportedState(deviceId, reportedState)
                .map(Result::success);
    }

    @GetMapping("/{deviceId}/delta")
    public Mono<Result<Map<String, Object>>> getDelta(@PathVariable String deviceId) {
        return deviceShadowService.getDelta(deviceId)
                .map(Result::success);
    }

    @PostMapping("/{deviceId}")
    public Mono<Result<DeviceShadow>> createShadow(@PathVariable String deviceId) {
        return deviceShadowService.createShadow(deviceId)
                .map(Result::success);
    }

    @DeleteMapping("/{deviceId}")
    public Mono<Result<Void>> deleteShadow(@PathVariable String deviceId) {
        return deviceShadowService.deleteShadow(deviceId)
                .then(Mono.just(Result.success()));
    }

    @GetMapping("/{deviceId}/metrics")
    public Mono<Result<Map<String, Object>>> getMonitorMetrics(
            @PathVariable String deviceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) String metricType) {
        return deviceShadowService.getMonitorMetrics(deviceId, startTime, endTime, metricType)
                .map(Result::success);
    }

    @GetMapping("/{deviceId}/health")
    public Mono<Result<Map<String, Object>>> getShadowHealthStatus(@PathVariable String deviceId) {
        return deviceShadowService.getShadowHealthStatus(deviceId)
                .map(Result::success);
    }

    @GetMapping("/status/{monitorStatus}")
    public Flux<Result<DeviceShadow>> getShadowsByMonitorStatus(@PathVariable String monitorStatus) {
        return deviceShadowService.getShadowsByMonitorStatus(monitorStatus)
                .map(Result::success);
    }

    @GetMapping("/monitor/status")
    public Mono<Result<Map<String, Object>>> getMonitorOverview() {
        return deviceShadowService.getMonitorOverview()
                .map(Result::success);
    }
}
