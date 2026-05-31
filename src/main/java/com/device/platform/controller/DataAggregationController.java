package com.device.platform.controller;

import com.device.platform.common.ApiResponse;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.AggregationResultResponse;
import com.device.platform.dto.DataPointIngestRequest;
import com.device.platform.entity.RawDataPoint;
import com.device.platform.aggregation.DataAggregationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/aggregation")
@RequiredArgsConstructor
public class DataAggregationController {

    private final DataAggregationService dataAggregationService;

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ApiResponse<List<RawDataPoint>>> ingestDataPoints(
            @Valid @RequestBody DataPointIngestRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return dataAggregationService.ingestDataPoints(request, ctx)
                .map(points -> {
                    ApiResponse<List<RawDataPoint>> response = ApiResponse.success(201, points);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/results")
    public Mono<ApiResponse<Flux<AggregationResultResponse>>> getResults(
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String metricName,
            @RequestParam(required = false) Long startTimeMs,
            @RequestParam(required = false) Long endTimeMs,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return Mono.just(ApiResponse.success(
                dataAggregationService.getAggregationResults(
                        deviceId, metricName, startTimeMs, endTimeMs, ctx)))
                .map(response -> {
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PostMapping("/upload")
    public Mono<ApiResponse<Long>> uploadResults(
            @RequestParam(required = false) String deviceId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return dataAggregationService.uploadAggregationResults(deviceId, ctx)
                .map(count -> {
                    ApiResponse<Long> response = ApiResponse.success(count);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/pending/count")
    public Mono<ApiResponse<Long>> getPendingUploadCount(
            @RequestParam(required = false) String deviceId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return dataAggregationService.getPendingUploadCount(deviceId, ctx)
                .map(count -> {
                    ApiResponse<Long> response = ApiResponse.success(count);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }
}
