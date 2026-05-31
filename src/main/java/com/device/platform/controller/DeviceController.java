package com.device.platform.controller;

import com.device.platform.common.ApiResponse;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.*;
import com.device.platform.entity.Device;
import com.device.platform.service.DeviceAuthService;
import com.device.platform.service.DeviceLifecycleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceLifecycleService deviceLifecycleService;
    private final DeviceAuthService deviceAuthService;

    @PostMapping("/activate")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ApiResponse<Device>> activateDevice(
            @Valid @RequestBody DeviceActivateRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return deviceLifecycleService.activateDevice(request, ctx)
                .map(device -> {
                    ApiResponse<Device> response = ApiResponse.success(201, device);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PostMapping("/auth")
    public Mono<ApiResponse<DeviceAuthResponse>> authenticate(
            @Valid @RequestBody DeviceAuthRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return deviceAuthService.authenticate(request, ctx)
                .map(auth -> {
                    ApiResponse<DeviceAuthResponse> response = ApiResponse.success(auth);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PostMapping("/auth/refresh")
    public Mono<ApiResponse<DeviceAuthResponse>> refreshToken(
            @RequestParam("refreshToken") String refreshToken,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return deviceAuthService.refreshToken(refreshToken, ctx)
                .map(auth -> {
                    ApiResponse<DeviceAuthResponse> response = ApiResponse.success(auth);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PostMapping("/auth/revoke")
    public Mono<ApiResponse<Void>> revokeToken(
            @RequestParam("token") String token,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return deviceAuthService.revokeToken(token, ctx)
                .then(Mono.fromCallable(() -> {
                    ApiResponse<Void> response = ApiResponse.success(null);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                }));
    }

    @GetMapping("/{deviceId}")
    public Mono<ApiResponse<Device>> getDevice(
            @PathVariable String deviceId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return deviceLifecycleService.getDevice(deviceId, ctx)
                .map(device -> {
                    ApiResponse<Device> response = ApiResponse.success(device);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/{deviceId}/status")
    public Mono<ApiResponse<ResourceResponse>> getDeviceStatus(
            @PathVariable String deviceId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return deviceLifecycleService.getDevice(deviceId, ctx)
                .map(device -> {
                    ResourceResponse resource = new ResourceResponse();
                    resource.setId(device.getDeviceId());
                    resource.setStatus(device.getStatus().name());
                    ApiResponse<ResourceResponse> response = ApiResponse.success(resource);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PutMapping("/{deviceId}/status")
    public Mono<ApiResponse<Device>> updateDeviceStatus(
            @PathVariable String deviceId,
            @Valid @RequestBody DeviceStatusUpdateRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return deviceLifecycleService.updateDeviceStatus(deviceId, request, ctx)
                .map(device -> {
                    ApiResponse<Device> response = ApiResponse.success(device);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PostMapping("/heartbeat")
    public Mono<ApiResponse<Device>> heartbeat(
            @Valid @RequestBody DeviceHeartbeatRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return deviceLifecycleService.heartbeat(request, ctx)
                .map(device -> {
                    ApiResponse<Device> response = ApiResponse.success(device);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PostMapping("/{deviceId}/deactivate")
    public Mono<ApiResponse<Void>> deactivateDevice(
            @PathVariable String deviceId,
            @RequestBody(required = false) DeviceDeactivateRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        if (request == null) {
            request = new DeviceDeactivateRequest();
        }
        return deviceLifecycleService.deactivateDevice(deviceId, request, ctx)
                .then(Mono.fromCallable(() -> {
                    ApiResponse<Void> response = ApiResponse.success(null);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                }));
    }
}
