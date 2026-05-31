package com.delivery.tracker.controller;

import com.delivery.tracker.common.Result;
import com.delivery.tracker.entity.Resource;
import com.delivery.tracker.service.CoreProcessingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final CoreProcessingService coreProcessingService;

    @PostMapping
    public Mono<Result<Map<String, Object>>> createResource(@RequestBody Map<String, Object> request) {
        String type = (String) request.get("type");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) request.get("config");
        @SuppressWarnings("unchecked")
        Map<String, String> labels = (Map<String, String>) request.get("labels");

        return coreProcessingService.createResource(type, config, labels)
                .map(resource -> Result.success(Map.of(
                        "id", resource.getId(),
                        "status", resource.getStatus()
                )));
    }

    @GetMapping("/{id}/status")
    public Mono<Result<Map<String, Object>>> getResourceStatus(@PathVariable String id) {
        return coreProcessingService.getResourceStatus(id)
                .map(Result::success);
    }

    @PostMapping("/batch")
    public Mono<Result<Map<String, Object>>> batchOperation(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) request.get("operations");

        return Mono.just(Result.success(Map.of(
                "batch_id", "batch_" + System.currentTimeMillis(),
                "results", operations.stream()
                        .map(op -> Map.of(
                                "id", op.get("id"),
                                "action", op.get("action"),
                                "status", "completed"
                        ))
                        .toList()
        )));
    }

    @PostMapping("/execute")
    public Mono<Result<Map<String, Object>>> executeHandler(@RequestBody Map<String, Object> request) {
        String traceId = (String) request.getOrDefault("traceId", "trace_" + System.currentTimeMillis());
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.get("params");
        String namespace = (String) request.getOrDefault("namespace", "default");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) request.get("payload");

        return coreProcessingService.executeHandler(traceId, params, namespace, payload)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }
}
