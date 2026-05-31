package com.device.platform.controller;

import com.device.platform.common.ApiResponse;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.*;
import com.device.platform.service.CoreProcessingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final CoreProcessingService coreProcessingService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ApiResponse<ResourceResponse>> createResource(
            @Valid @RequestBody ResourceCreateRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);

        ProcessRequest processRequest = new ProcessRequest();
        processRequest.setTraceId(ctx.getTraceId());
        processRequest.setNamespace("production");
        processRequest.setEntityType(request.getType());
        processRequest.setEntityId("rsc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", request.getType());
        payload.put("config", request.getConfig());
        payload.put("labels", request.getLabels());
        processRequest.setPayload(payload);

        if (request.getConfig() != null) {
            processRequest.setParams(new HashMap<>(request.getConfig()));
        }

        return coreProcessingService.executeHandler(processRequest)
                .map(result -> {
                    ResourceResponse resource = new ResourceResponse();
                    resource.setId(processRequest.getEntityId());
                    resource.setStatus("provisioning");
                    resource.setProgress(result.getProgress() != null ? result.getProgress() : 0.0);

                    ApiResponse<ResourceResponse> response = ApiResponse.success(201, resource);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/{id}/status")
    public Mono<ApiResponse<ResourceResponse>> getResourceStatus(
            @PathVariable String id,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);

        if (id.startsWith("run_")) {
            return coreProcessingService.getRunStatus(id, ctx)
                    .map(status -> {
                        ResourceResponse resource = new ResourceResponse();
                        resource.setId(status.getRunId());
                        resource.setStatus(status.getStatus());
                        resource.setProgress(status.getProgress());

                        ApiResponse<ResourceResponse> response = ApiResponse.success(resource);
                        response.setTraceId(ctx.getTraceId());
                        return response;
                    });
        }

        ResourceResponse resource = new ResourceResponse();
        resource.setId(id);
        resource.setStatus("running");
        resource.setProgress(0.8);

        ApiResponse<ResourceResponse> response = ApiResponse.success(resource);
        response.setTraceId(ctx.getTraceId());
        return Mono.just(response);
    }

    @PostMapping("/batch")
    public Mono<ApiResponse<BatchOperationResponse>> batchOperations(
            @Valid @RequestBody BatchOperationRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);

        return coreProcessingService.executeBatch(request, ctx)
                .map(result -> {
                    ApiResponse<BatchOperationResponse> response = ApiResponse.success(result);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PostMapping("/process")
    public Mono<ApiResponse<ProcessResponse>> process(
            @Valid @RequestBody ProcessRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        if (request.getTraceId() == null || request.getTraceId().isEmpty()) {
            request.setTraceId(ctx.getTraceId());
        }

        return coreProcessingService.executeHandler(request)
                .map(result -> {
                    ApiResponse<ProcessResponse> response = ApiResponse.success(result);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/runs/{runId}")
    public Mono<ApiResponse<RunStatusResponse>> getRunStatus(
            @PathVariable String runId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);

        return coreProcessingService.getRunStatus(runId, ctx)
                .map(result -> {
                    ApiResponse<RunStatusResponse> response = ApiResponse.success(result);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }
}
