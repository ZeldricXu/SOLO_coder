package com.tracetopology.web.controller;

import com.tracetopology.api.service.StorageManagementService;
import com.tracetopology.common.result.Result;
import com.tracetopology.domain.storage.StoredFile;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageManagementService;

    @PostMapping("/upload")
    public Mono<Result<StoredFile>> uploadFile(
            @RequestParam String bucket,
            @RequestParam String path,
            @RequestPart("file") FilePart filePart) {

        Flux<DataBuffer> content = filePart.content();

        return content.collectList()
                .flatMap(buffers -> {
                    int totalSize = buffers.stream().mapToInt(DataBuffer::readableByteCount).sum();
                    byte[] bytes = new byte[totalSize];
                    int offset = 0;
                    for (DataBuffer buffer : buffers) {
                        int length = buffer.readableByteCount();
                        buffer.read(bytes, offset, length);
                        offset += length;
                    }

                    return Mono.fromCallable(() -> {
                        StoredFile file = storageManagementService.storeFile(
                                bucket, path, bytes, filePart.filename());
                        return Result.success(file);
                    });
                });
    }

    @GetMapping("/files/{bucket}/**")
    public Mono<Result<StoredFile>> getFile(
            @PathVariable String bucket,
            @RequestParam String path) {
        return Mono.fromCallable(() -> {
            StoredFile file = storageManagementService.getFile(bucket, path);
            return Result.success(file);
        });
    }

    @DeleteMapping("/files/{bucket}/**")
    public Mono<Result<Void>> deleteFile(
            @PathVariable String bucket,
            @RequestParam String path) {
        return Mono.fromCallable(() -> {
            storageManagementService.deleteFile(bucket, path);
            return Result.success();
        });
    }

    @GetMapping("/files")
    public Mono<Result<List<StoredFile>>> listFiles(
            @RequestParam String bucket,
            @RequestParam(defaultValue = "") String prefix) {
        return Mono.fromCallable(() -> {
            List<StoredFile> files = storageManagementService.listFiles(bucket, prefix);
            return Result.success(files);
        });
    }

    @PostMapping("/lifecycle")
    public Mono<Result<String>> setLifecyclePolicy(
            @RequestBody LifecyclePolicyRequest request) {
        return Mono.fromCallable(() -> {
            storageManagementService.setLifecyclePolicy(
                    request.getBucket(),
                    request.getRules()
            );
            return Result.success("Lifecycle policy applied");
        });
    }

    @PostMapping("/cleanup")
    public Mono<Result<Map<String, Object>>> cleanupExpiredFiles() {
        return Mono.fromCallable(() -> {
            int count = storageManagementService.cleanupExpiredFiles();
            Map<String, Object> result = Map.of(
                    "cleanedCount", count,
                    "timestamp", Instant.now().toString()
            );
            return Result.success(result);
        });
    }

    @GetMapping("/usage")
    public Mono<Result<Map<String, Object>>> getStorageUsage(@RequestParam String bucket) {
        return Mono.fromCallable(() -> {
            Map<String, Object> usage = storageManagementService.getStorageUsage(bucket);
            return Result.success(usage);
        });
    }

    @PostMapping("/autoscale")
    public Mono<Result<Map<String, Object>>> triggerAutoScale() {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = storageManagementService.triggerAutoScale();
            return Result.success(result);
        });
    }

    @Data
    public static class LifecyclePolicyRequest {
        private String bucket;
        private List<Map<String, Object>> rules;
    }
}
