package com.device.platform.controller;

import com.device.platform.common.ApiResponse;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.OfflineDataRequest;
import com.device.platform.entity.OfflineData;
import com.device.platform.cache.OfflineDataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/offline")
@RequiredArgsConstructor
public class OfflineDataController {

    private final OfflineDataService offlineDataService;

    @PostMapping("/cache")
    public Mono<ApiResponse<OfflineData>> cacheData(
            @Valid @RequestBody OfflineDataRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return offlineDataService.cacheOfflineData(request, ctx)
                .map(data -> {
                    ApiResponse<OfflineData> response = ApiResponse.success(201, data);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/pending")
    public Mono<ApiResponse<Flux<OfflineData>>> getPendingData(
            @RequestParam(required = false) String deviceId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return Mono.just(ApiResponse.success(offlineDataService.getPendingData(deviceId, ctx)))
                .map(response -> {
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/pending/count")
    public Mono<ApiResponse<Long>> getPendingCount(
            @RequestParam(required = false) String deviceId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return offlineDataService.getPendingCount(deviceId, ctx)
                .map(count -> {
                    ApiResponse<Long> response = ApiResponse.success(count);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PostMapping("/network/{status}")
    public Mono<ApiResponse<Void>> setNetworkStatus(
            @PathVariable boolean status,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return offlineDataService.setNetworkStatus(status, ctx)
                .then(Mono.fromCallable(() -> {
                    ApiResponse<Void> response = ApiResponse.success(null);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                }));
    }

    @GetMapping("/network/status")
    public Mono<ApiResponse<Boolean>> getNetworkStatus(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return offlineDataService.checkNetworkStatus(ctx)
                .map(available -> {
                    ApiResponse<Boolean> response = ApiResponse.success(available);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PostMapping("/sync")
    public Mono<ApiResponse<Void>> triggerSync(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return offlineDataService.setNetworkStatus(true, ctx)
                .then(Mono.fromCallable(() -> {
                    ApiResponse<Void> response = ApiResponse.success(null);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                }));
    }
}
