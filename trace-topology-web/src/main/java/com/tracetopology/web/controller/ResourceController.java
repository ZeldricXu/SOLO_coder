package com.tracetopology.web.controller;

import com.tracetopology.api.service.CoreProcessingService;
import com.tracetopology.common.result.Result;
import com.tracetopology.domain.entity.Entity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final CoreProcessingService coreProcessingService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Result<Map<String, Object>>> createResource(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @Valid @RequestBody CreateResourceRequest request) {
        return Mono.fromCallable(() -> {
            log.info("创建资源: traceId={}, type={}", traceId, request.getType());

            Map<String, Object> params = Map.of(
                    "requestId", java.util.UUID.randomUUID().toString(),
                    "timestamp", System.currentTimeMillis()
            );

            Map<String, Object> result = coreProcessingService.process(
                    traceId,
                    request.getNamespace(),
                    request.getConfig(),
                    params
            );

            if (result == null) {
                return Result.error(422, "参数校验失败");
            }

            return Result.success(result);
        });
    }

    @GetMapping("/{id}/status")
    public Mono<Result<Map<String, Object>>> getResourceStatus(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            Entity entity = coreProcessingService.getEntity(id);
            Map<String, Object> status = Map.of(
                    "id", entity.getId(),
                    "status", entity.getStatus(),
                    "progress", 0.8,
                    "type", entity.getType(),
                    "updatedAt", entity.getUpdatedAt()
            );
            return Result.success(status);
        });
    }

    @PostMapping("/batch")
    public Mono<Result<Map<String, Object>>> batchOperation(
            @RequestBody BatchOperationRequest request) {
        return Mono.fromCallable(() -> {
            log.info("批量操作: operations={}", request.getOperations().size());

            Map<String, Object> result = Map.of(
                    "batchId", "batch_" + System.currentTimeMillis(),
                    "results", request.getOperations().stream()
                            .map(op -> Map.of(
                                    "id", op.getId(),
                                    "action", op.getAction(),
                                    "status", "accepted"
                            ))
                            .toList()
            );

            return Result.success(result);
        });
    }

    @Data
    public static class CreateResourceRequest {
        @NotBlank(message = "资源类型不能为空")
        private String type;

        private String namespace = "default";

        private Map<String, Object> config = Map.of();

        private Map<String, String> labels = Map.of();
    }

    @Data
    public static class BatchOperationRequest {
        private java.util.List<BatchOperation> operations;
    }

    @Data
    public static class BatchOperation {
        private String action;
        private String id;
    }
}
