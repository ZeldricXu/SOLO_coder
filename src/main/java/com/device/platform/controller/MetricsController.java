package com.device.platform.controller;

import com.device.platform.common.ApiResponse;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.MetricsResponse;
import com.device.platform.monitor.MetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsService metricsService;

    @GetMapping("/realtime")
    public Mono<ApiResponse<Map<String, Object>>> getRealtimeMetrics(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return metricsService.getRealtimeMetrics(ctx)
                .map(metrics -> {
                    ApiResponse<Map<String, Object>> response = ApiResponse.success(metrics);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/snapshots/latest")
    public Mono<ApiResponse<MetricsResponse>> getLatestSnapshot(
            @RequestParam(required = false) String metricType,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return metricsService.getLatestSnapshot(metricType, ctx)
                .map(snapshot -> {
                    ApiResponse<MetricsResponse> response = ApiResponse.success(snapshot);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/snapshots")
    public Mono<ApiResponse<Flux<MetricsResponse>>> getSnapshots(
            @RequestParam(required = false) String metricType,
            @RequestParam(required = false) Long startTimeMs,
            @RequestParam(required = false) Long endTimeMs,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return Mono.just(ApiResponse.success(
                metricsService.getSnapshots(metricType, startTimeMs, endTimeMs, ctx)))
                .map(response -> {
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }
}
