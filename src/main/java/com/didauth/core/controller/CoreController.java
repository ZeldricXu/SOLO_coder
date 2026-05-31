package com.didauth.core.controller;

import com.didauth.common.response.ApiResponse;
import com.didauth.core.context.RequestContext;
import com.didauth.core.engine.CoreEngine;
import com.didauth.core.entity.SysConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CoreController {

    private final CoreEngine coreEngine;

    @PostMapping("/resources")
    public Mono<ApiResponse<ResourceResponse>> createResource(@Valid @RequestBody ResourceRequest request) {
        RequestContext ctx = RequestContext.create(UUID.randomUUID().toString().replace("-", ""));
        ctx.setModule("core");
        ctx.setOperation("createResource");

        return coreEngine.execute(ctx, request, req -> {
            String id = "rsc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            ResourceResponse response = new ResourceResponse();
            response.setId(id);
            response.setStatus("provisioning");
            return Mono.just(response);
        }, "createResource").map(r -> ApiResponse.success(201, r));
    }

    @GetMapping("/resources/{id}/status")
    public Mono<ApiResponse<ResourceStatusResponse>> getResourceStatus(@PathVariable String id) {
        RequestContext ctx = RequestContext.create(UUID.randomUUID().toString().replace("-", ""));
        ctx.setModule("core");
        ctx.setOperation("getResourceStatus");

        return coreEngine.execute(ctx, id, resourceId -> {
            ResourceStatusResponse response = new ResourceStatusResponse();
            response.setId(resourceId);
            response.setStatus("completed");
            response.setProgress(1.0);
            return Mono.just(response);
        }, "getResourceStatus").map(ApiResponse::success);
    }

    @PostMapping("/resources/batch")
    public Mono<ApiResponse<BatchResponse>> batchOperation(@Valid @RequestBody BatchRequest request) {
        RequestContext ctx = RequestContext.create(UUID.randomUUID().toString().replace("-", ""));
        ctx.setModule("core");
        ctx.setOperation("batchOperation");

        return coreEngine.execute(ctx, request, req -> {
            String batchId = "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            BatchResponse response = new BatchResponse();
            response.setBatchId(batchId);
            response.setResults(req.getOperations().stream()
                    .map(op -> {
                        BatchResult result = new BatchResult();
                        result.setId(op.getId());
                        result.setAction(op.getAction());
                        result.setStatus("success");
                        return result;
                    })
                    .toList());
            return Mono.just(response);
        }, "batchOperation").map(ApiResponse::success);
    }

    @GetMapping("/configs/{namespace}/{configId}")
    public Mono<ApiResponse<Map<String, Object>>> getConfig(
            @PathVariable String namespace,
            @PathVariable String configId) {
        return coreEngine.loadConfig(namespace, configId)
                .map(ApiResponse::success);
    }

    @PostMapping("/configs/{namespace}/{configId}")
    public Mono<ApiResponse<SysConfig>> saveConfig(
            @PathVariable String namespace,
            @PathVariable String configId,
            @RequestBody Map<String, Object> parameters) {
        return coreEngine.saveConfig(namespace, configId, parameters)
                .map(ApiResponse::success);
    }

    @Data
    public static class ResourceRequest implements Serializable {
        @NotBlank
        private String type;
        private Map<String, Object> config;
        private Map<String, String> labels;
    }

    @Data
    public static class ResourceResponse implements Serializable {
        private String id;
        private String status;
    }

    @Data
    public static class ResourceStatusResponse implements Serializable {
        private String id;
        private String status;
        private Double progress;
    }

    @Data
    public static class BatchRequest implements Serializable {
        private List<BatchOperation> operations;
    }

    @Data
    public static class BatchOperation implements Serializable {
        private String action;
        private String id;
    }

    @Data
    public static class BatchResponse implements Serializable {
        private String batchId;
        private List<BatchResult> results;
    }

    @Data
    public static class BatchResult implements Serializable {
        private String id;
        private String action;
        private String status;
    }
}
