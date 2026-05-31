package com.cdcsync.common.controller;

import com.cdcsync.common.api.Result;
import com.cdcsync.common.dto.BatchOperationRequest;
import com.cdcsync.common.dto.BatchOperationResponse;
import com.cdcsync.common.dto.ResourceCreateRequest;
import com.cdcsync.common.dto.ResourceStatusResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    @PostMapping
    public Mono<Result<ResourceStatusResponse>> createResource(@Valid @RequestBody ResourceCreateRequest request) {
        log.info("Creating resource of type: {}", request.getType());
        String resourceId = "rsc_" + UUID.randomUUID().toString().substring(0, 6);
        return Mono.just(Result.success(ResourceStatusResponse.builder()
                .id(resourceId)
                .status("provisioning")
                .progress(0.0)
                .build()));
    }

    @GetMapping("/{id}/status")
    public Mono<Result<ResourceStatusResponse>> getResourceStatus(@PathVariable String id) {
        return Mono.just(Result.success(ResourceStatusResponse.builder()
                .id(id)
                .status("completed")
                .progress(1.0)
                .build()));
    }

    @PostMapping("/batch")
    public Mono<Result<BatchOperationResponse>> executeBatch(@Valid @RequestBody BatchOperationRequest request) {
        String batchId = "batch_" + UUID.randomUUID().toString().substring(0, 6);
        var results = new ArrayList<BatchOperationResponse.OperationResult>();

        for (var op : request.getOperations()) {
            results.add(BatchOperationResponse.OperationResult.builder()
                    .id(op.getId())
                    .action(op.getAction())
                    .success(true)
                    .message("Operation completed")
                    .build());
        }

        return Mono.just(Result.success(BatchOperationResponse.builder()
                .batchId(batchId)
                .results(results)
                .build()));
    }
}
