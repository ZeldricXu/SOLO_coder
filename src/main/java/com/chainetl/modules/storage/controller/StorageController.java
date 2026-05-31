package com.chainetl.modules.storage.controller;

import com.chainetl.common.dto.ApiResponse;
import com.chainetl.modules.storage.dto.*;
import com.chainetl.modules.storage.service.StorageBatchScheduler;
import com.chainetl.modules.storage.service.StorageService;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;
    private final StorageBatchScheduler batchScheduler;

    @PostMapping("/store")
    @Timed(value = "storage.content.store", description = "Time taken to store content")
    public Mono<ResponseEntity<ApiResponse<StorageRecordResponse>>> storeContent(
            @Valid @RequestBody StoreContentRequest request) {
        return storageService.storeContent(request)
                .map(record -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success(201, record)));
    }

    @PostMapping("/store/batch")
    @Timed(value = "storage.content.batch.store", description = "Time taken to store content in batch")
    public Mono<ResponseEntity<ApiResponse<BatchStoreResponse>>> batchStoreContent(
            @Valid @RequestBody BatchStoreContentRequest request) {
        return storageService.batchStoreContent(request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success(201, response)));
    }

    @PostMapping("/store/batch-merge")
    @Timed(value = "storage.content.batch.merge", description = "Time taken to enqueue for batch merge")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> enqueueForBatchMerge(
            @Valid @RequestBody StoreContentRequest request) {
        return Mono.fromCallable(() -> {
            batchScheduler.enqueue(request);
            return ResponseEntity.accepted()
                    .body(ApiResponse.success(202, Map.of(
                            "status", "QUEUED",
                            "queueSize", batchScheduler.getPendingQueueSize()
                    )));
        });
    }

    @PostMapping("/retrieve/batch")
    @Timed(value = "storage.content.batch.retrieve", description = "Time taken to retrieve content in batch")
    public Mono<ResponseEntity<ApiResponse<BatchRetrieveResponse>>> batchRetrieveContent(
            @Valid @RequestBody BatchRetrieveRequest request) {
        return storageService.batchRetrieveContent(request)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/{recordId}")
    @Timed(value = "storage.content.retrieve", description = "Time taken to retrieve content")
    public Mono<ResponseEntity<ApiResponse<RetrieveContentResponse>>> retrieveContent(
            @PathVariable String recordId) {
        return storageService.retrieveContent(recordId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/{recordId}/record")
    @Timed(value = "storage.record.get", description = "Time taken to get storage record")
    public Mono<ResponseEntity<ApiResponse<StorageRecordResponse>>> getRecord(
            @PathVariable String recordId) {
        return storageService.getRecord(recordId)
                .map(record -> ResponseEntity.ok(ApiResponse.success(record)));
    }

    @GetMapping("/hash/{contentHash}")
    @Timed(value = "storage.record.hash", description = "Time taken to get storage record by hash")
    public Mono<ResponseEntity<ApiResponse<StorageRecordResponse>>> getRecordByHash(
            @PathVariable String contentHash) {
        return storageService.getRecordByHash(contentHash)
                .map(record -> ResponseEntity.ok(ApiResponse.success(record)));
    }

    @PostMapping("/{recordId}/pin")
    @Timed(value = "storage.content.pin", description = "Time taken to pin content")
    public Mono<ResponseEntity<ApiResponse<StorageRecordResponse>>> pinContent(
            @PathVariable String recordId) {
        return storageService.pinContent(recordId)
                .map(record -> ResponseEntity.ok(ApiResponse.success(record)));
    }

    @PostMapping("/{recordId}/unpin")
    @Timed(value = "storage.content.unpin", description = "Time taken to unpin content")
    public Mono<ResponseEntity<ApiResponse<StorageRecordResponse>>> unpinContent(
            @PathVariable String recordId) {
        return storageService.unpinContent(recordId)
                .map(record -> ResponseEntity.ok(ApiResponse.success(record)));
    }

    @GetMapping
    @Timed(value = "storage.record.list", description = "Time taken to list storage records")
    public Mono<ResponseEntity<ApiResponse<List<StorageRecordResponse>>>> listRecords(
            @RequestParam(required = false) String storageType,
            @RequestParam(required = false) String pinStatus) {
        return storageService.listRecords(storageType, pinStatus)
                .map(records -> ResponseEntity.ok(ApiResponse.success(records)));
    }

    @GetMapping("/batch/status")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> getBatchStatus() {
        return Mono.fromCallable(() -> ResponseEntity.ok(
                ApiResponse.success(Map.of(
                        "pendingQueueSize", batchScheduler.getPendingQueueSize()
                ))));
    }
}
