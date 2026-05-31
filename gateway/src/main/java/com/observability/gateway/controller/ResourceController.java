package com.observability.gateway.controller;

import com.observability.common.dto.ApiResponse;
import com.observability.common.dto.BatchOperationRequest;
import com.observability.common.dto.BatchOperationResponse;
import com.observability.common.dto.ResourceCreateRequest;
import com.observability.common.dto.ResourceStatusResponse;
import com.observability.gateway.service.ResourceMutationService;
import com.observability.gateway.service.ResourceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceQueryService resourceQueryService;
    private final ResourceMutationService resourceMutationService;

    @PostMapping
    public Mono<ApiResponse<Map<String, Object>>> createResource(@RequestBody ResourceCreateRequest request) {
        return resourceMutationService.createResource(request)
                .map(data -> {
                    ApiResponse<Map<String, Object>> response = ApiResponse.success(data);
                    response.setCode(201);
                    return response;
                });
    }

    @GetMapping("/{id}/status")
    public Mono<ApiResponse<ResourceStatusResponse>> getResourceStatus(@PathVariable String id) {
        return resourceQueryService.getResourceStatus(id)
                .map(ApiResponse::success);
    }

    @PostMapping("/batch")
    public Mono<ApiResponse<BatchOperationResponse>> batchOperation(@RequestBody BatchOperationRequest request) {
        return resourceMutationService.batchOperation(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/{id}/start")
    public Mono<ApiResponse<String>> startResource(@PathVariable String id) {
        return resourceMutationService.startResource(id)
                .then(Mono.just(ApiResponse.success("Resource started successfully")));
    }

    @PostMapping("/{id}/stop")
    public Mono<ApiResponse<String>> stopResource(@PathVariable String id) {
        return resourceMutationService.stopResource(id)
                .then(Mono.just(ApiResponse.success("Resource stopped successfully")));
    }

    @PostMapping("/{id}/restart")
    public Mono<ApiResponse<String>> restartResource(@PathVariable String id) {
        return resourceMutationService.restartResource(id)
                .then(Mono.just(ApiResponse.success("Resource restarted successfully")));
    }

    @DeleteMapping("/{id}")
    public Mono<ApiResponse<String>> deleteResource(@PathVariable String id) {
        return resourceMutationService.deleteResource(id)
                .then(Mono.just(ApiResponse.success("Resource deleted successfully")));
    }
}
