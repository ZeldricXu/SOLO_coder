package com.device.platform.controller;

import com.device.platform.common.ApiResponse;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.DeviceShadowResponse;
import com.device.platform.dto.DeviceShadowUpdateRequest;
import com.device.platform.shadow.DeviceShadowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/shadow")
@RequiredArgsConstructor
public class DeviceShadowController {

    private final DeviceShadowService deviceShadowService;

    @GetMapping("/{deviceId}")
    public Mono<ApiResponse<DeviceShadowResponse>> getShadow(
            @PathVariable String deviceId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return deviceShadowService.getShadow(deviceId, ctx)
                .map(shadow -> {
                    ApiResponse<DeviceShadowResponse> response = ApiResponse.success(shadow);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PutMapping("/{deviceId}/desired")
    public Mono<ApiResponse<DeviceShadowResponse>> updateDesiredState(
            @PathVariable String deviceId,
            @Valid @RequestBody DeviceShadowUpdateRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        request.setDeviceId(deviceId);
        return deviceShadowService.updateDesiredState(request, ctx)
                .map(shadow -> {
                    ApiResponse<DeviceShadowResponse> response = ApiResponse.success(shadow);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PutMapping("/{deviceId}/reported")
    public Mono<ApiResponse<DeviceShadowResponse>> updateReportedState(
            @PathVariable String deviceId,
            @Valid @RequestBody DeviceShadowUpdateRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        request.setDeviceId(deviceId);
        return deviceShadowService.updateReportedState(request, ctx)
                .map(shadow -> {
                    ApiResponse<DeviceShadowResponse> response = ApiResponse.success(shadow);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PostMapping("/{deviceId}/sync")
    public Mono<ApiResponse<Void>> syncShadow(
            @PathVariable String deviceId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return deviceShadowService.syncShadow(deviceId, ctx)
                .then(Mono.fromCallable(() -> {
                    ApiResponse<Void> response = ApiResponse.success(null);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                }));
    }

    @DeleteMapping("/{deviceId}")
    public Mono<ApiResponse<Void>> deleteShadow(
            @PathVariable String deviceId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return deviceShadowService.deleteShadow(deviceId, ctx)
                .then(Mono.fromCallable(() -> {
                    ApiResponse<Void> response = ApiResponse.success(null);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                }));
    }
}
