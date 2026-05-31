package com.device.platform.controller;

import com.device.platform.common.ApiResponse;
import com.device.platform.common.EntityStatus;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.InferenceTaskCreateRequest;
import com.device.platform.dto.InferenceTaskResultRequest;
import com.device.platform.entity.InferenceModel;
import com.device.platform.entity.InferenceTask;
import com.device.platform.inference.InferenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/inference")
@RequiredArgsConstructor
public class InferenceController {

    private final InferenceService inferenceService;

    @PostMapping("/models")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ApiResponse<InferenceModel>> deployModel(
            @Valid @RequestBody InferenceModel model,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return inferenceService.deployModel(model, ctx)
                .map(deployed -> {
                    ApiResponse<InferenceModel> response = ApiResponse.success(201, deployed);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/models")
    public Mono<ApiResponse<List<InferenceModel>>> listModels(
            @RequestParam(required = false) String modelType,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return inferenceService.listModels(modelType, ctx)
                .map(models -> {
                    ApiResponse<List<InferenceModel>> response = ApiResponse.success(models);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @DeleteMapping("/models/{modelId}")
    public Mono<ApiResponse<Void>> undeployModel(
            @PathVariable String modelId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return inferenceService.undeployModel(modelId, ctx)
                .then(Mono.fromCallable(() -> {
                    ApiResponse<Void> response = ApiResponse.success(null);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                }));
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ApiResponse<InferenceTask>> createTask(
            @Valid @RequestBody InferenceTaskCreateRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return inferenceService.createTask(request, ctx)
                .map(task -> {
                    ApiResponse<InferenceTask> response = ApiResponse.success(201, task);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PostMapping("/tasks/result")
    public Mono<ApiResponse<InferenceTask>> reportResult(
            @Valid @RequestBody InferenceTaskResultRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return inferenceService.reportTaskResult(request, ctx)
                .map(task -> {
                    ApiResponse<InferenceTask> response = ApiResponse.success(task);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/tasks/{taskId}")
    public Mono<ApiResponse<InferenceTask>> getTaskStatus(
            @PathVariable String taskId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return inferenceService.getTaskStatus(taskId, ctx)
                .map(task -> {
                    ApiResponse<InferenceTask> response = ApiResponse.success(task);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/devices/{deviceId}/tasks")
    public Mono<ApiResponse<Flux<InferenceTask>>> listDeviceTasks(
            @PathVariable String deviceId,
            @RequestParam(required = false) EntityStatus status,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return Mono.just(ApiResponse.success(inferenceService.listDeviceTasks(deviceId, status, ctx)))
                .map(response -> {
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }
}
