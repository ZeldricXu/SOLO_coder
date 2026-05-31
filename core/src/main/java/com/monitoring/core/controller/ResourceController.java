package com.monitoring.core.controller;

import com.monitoring.common.dto.*;
import com.monitoring.core.handler.RequestHandler;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ResourceController {

    private final RequestHandler requestHandler;

    @PostMapping("/resources")
    public Mono<ApiResponse<ResourceCreateResponse>> createResource(
            @Valid @RequestBody ResourceCreateRequest request) {
        return requestHandler.handleCreateResource(request);
    }

    @GetMapping("/resources/{id}/status")
    public Mono<ApiResponse<ResourceStatusResponse>> getResourceStatus(@PathVariable String id) {
        return requestHandler.handleGetStatus(id);
    }

    @PostMapping("/resources/batch")
    public Mono<ApiResponse<BatchOperationResponse>> batchOperation(
            @Valid @RequestBody BatchOperationRequest request) {
        return requestHandler.handleBatchOperation(request);
    }
}
