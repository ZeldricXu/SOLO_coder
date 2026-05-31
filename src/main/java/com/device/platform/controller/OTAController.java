package com.device.platform.controller;

import com.device.platform.common.ApiResponse;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.*;
import com.device.platform.entity.Firmware;
import com.device.platform.entity.OTAJob;
import com.device.platform.ota.OTAService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ota")
@RequiredArgsConstructor
public class OTAController {

    private final OTAService otaService;

    @PostMapping("/firmware")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ApiResponse<Firmware>> uploadFirmware(
            @Valid @RequestBody FirmwareUploadRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return otaService.uploadFirmware(request, ctx)
                .map(firmware -> {
                    ApiResponse<Firmware> response = ApiResponse.success(201, firmware);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/firmware")
    public Mono<ApiResponse<List<Firmware>>> listFirmwares(
            @RequestParam(required = false) String productKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return otaService.listFirmwares(productKey, ctx)
                .map(firmwares -> {
                    ApiResponse<List<Firmware>> response = ApiResponse.success(firmwares);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ApiResponse<OTAJob>> createOTAJob(
            @Valid @RequestBody OTAJobCreateRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return otaService.createOTAJob(request, ctx)
                .map(job -> {
                    ApiResponse<OTAJob> response = ApiResponse.success(201, job);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/jobs/{jobId}")
    public Mono<ApiResponse<OTAJob>> getJobStatus(
            @PathVariable String jobId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return otaService.getJobStatus(jobId, ctx)
                .map(job -> {
                    ApiResponse<OTAJob> response = ApiResponse.success(job);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PostMapping("/progress")
    public Mono<ApiResponse<Void>> updateProgress(
            @Valid @RequestBody OTAProgressUpdateRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return otaService.updateUpgradeProgress(request, ctx)
                .then(Mono.fromCallable(() -> {
                    ApiResponse<Void> response = ApiResponse.success(null);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                }));
    }
}
