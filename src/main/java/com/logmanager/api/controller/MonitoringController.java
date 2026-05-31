package com.logmanager.api.controller;

import com.logmanager.api.vo.ApiResponse;
import com.logmanager.service.LogPipelineService;
import com.logmanager.service.MetricsService;
import com.logmanager.service.MonitoringService;
import com.logmanager.service.SLOService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final MonitoringService monitoringService;
    private final LogPipelineService logPipelineService;
    private final MetricsService metricsService;
    private final SLOService sloService;

    @GetMapping("/system")
    public Mono<ApiResponse<Map<String, Object>>> getSystemMetrics() {
        return monitoringService.getSystemMetrics()
                .map(ApiResponse::success);
    }

    @GetMapping("/service/{serviceName}")
    public Mono<ApiResponse<Map<String, Object>>> getServiceMetrics(@PathVariable String serviceName) {
        return monitoringService.getServiceMetrics(serviceName)
                .map(ApiResponse::success);
    }

    @GetMapping("/jvm")
    public Mono<ApiResponse<Map<String, Object>>> getJvmMetrics() {
        return monitoringService.getJvmMetrics()
                .map(ApiResponse::success);
    }

    @GetMapping("/database")
    public Mono<ApiResponse<Map<String, Object>>> getDatabaseMetrics() {
        return monitoringService.getDatabaseMetrics()
                .map(ApiResponse::success);
    }

    @GetMapping("/cache")
    public Mono<ApiResponse<Map<String, Object>>> getCacheMetrics() {
        return monitoringService.getCacheMetrics()
                .map(ApiResponse::success);
    }

    @GetMapping("/health")
    public Mono<ApiResponse<Map<String, Object>>> getHealthStatus() {
        return monitoringService.getHealthStatus()
                .map(ApiResponse::success);
    }

    @PostMapping("/record/latency")
    public Mono<ApiResponse<Void>> recordLatency(@RequestParam String operation, @RequestParam long latencyMs) {
        monitoringService.recordLatency(operation, latencyMs);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/record/error")
    public Mono<ApiResponse<Void>> recordError(@RequestParam String operation) {
        monitoringService.recordError(operation);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/record/success")
    public Mono<ApiResponse<Void>> recordSuccess(@RequestParam String operation) {
        monitoringService.recordSuccess(operation);
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/log-pipeline/cache")
    public Mono<ApiResponse<Map<String, Object>>> getLogPipelineCacheStats() {
        return logPipelineService.getCacheStats()
                .map(ApiResponse::success);
    }

    @PostMapping("/log-pipeline/cache/warmup")
    public Mono<ApiResponse<Void>> warmupLogPipelineCache() {
        return logPipelineService.warmupCache()
                .thenReturn(ApiResponse.success(null));
    }

    @DeleteMapping("/log-pipeline/cache/{traceId}")
    public Mono<ApiResponse<Void>> invalidateLogPipelineCache(@PathVariable String traceId) {
        return logPipelineService.invalidateCache(traceId)
                .thenReturn(ApiResponse.success(null));
    }

    @DeleteMapping("/log-pipeline/cache")
    public Mono<ApiResponse<Void>> invalidateAllLogPipelineCache() {
        return logPipelineService.invalidateAllCache()
                .thenReturn(ApiResponse.success(null));
    }

    @GetMapping("/metrics/batch")
    public Mono<ApiResponse<Map<String, Object>>> getMetricsBatchStats() {
        return metricsService.getBatchStats()
                .map(ApiResponse::success);
    }

    @GetMapping("/slo")
    public Mono<ApiResponse<Map<String, Object>>> getSLOMonitoringStats() {
        return sloService.getMonitoringStats()
                .map(ApiResponse::success);
    }
}
