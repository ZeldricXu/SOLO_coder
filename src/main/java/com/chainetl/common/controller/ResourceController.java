package com.chainetl.common.controller;

import com.chainetl.common.dto.ApiResponse;
import com.chainetl.common.dto.BatchOperationRequest;
import com.chainetl.common.dto.BatchOperationResponse;
import com.chainetl.common.dto.ResourceRequest;
import com.chainetl.common.dto.ResourceStatus;
import com.chainetl.common.service.ResourceManagementService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceManagementService resourceService;

    @PostMapping
    @Timed(value = "resource.create", description = "Time taken to create a resource")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> createResource(
            @Valid @RequestBody ResourceRequest request) {
        return resourceService.createResource(request)
                .map(result -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success(201, result)));
    }

    @GetMapping("/{id}/status")
    @Timed(value = "resource.status.get", description = "Time taken to get resource status")
    public Mono<ResponseEntity<ApiResponse<ResourceStatus>>> getResourceStatus(
            @PathVariable String id) {
        return resourceService.getResourceStatus(id)
                .map(status -> ResponseEntity.ok(ApiResponse.success(status)));
    }

    @PostMapping("/batch")
    @Timed(value = "resource.batch.operation", description = "Time taken to execute batch operations")
    public Mono<ResponseEntity<ApiResponse<BatchOperationResponse>>> executeBatchOperation(
            @Valid @RequestBody BatchOperationRequest request) {
        return resourceService.executeBatchOperation(request)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }
}
