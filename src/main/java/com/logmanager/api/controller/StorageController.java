package com.logmanager.api.controller;

import com.logmanager.api.vo.ApiResponse;
import com.logmanager.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    @PostMapping("/{bucket}/{key}")
    public Mono<ApiResponse<String>> uploadObject(
            @PathVariable String bucket,
            @PathVariable String key,
            @RequestPart("file") FilePart filePart,
            @RequestHeader Map<String, String> headers) {
        return filePart.content()
                .collectList()
                .flatMap(dataBuffers -> {
                    java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
                    dataBuffers.forEach(buffer -> {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        outputStream.write(bytes, 0, bytes.length);
                    });
                    java.io.InputStream inputStream = new java.io.ByteArrayInputStream(outputStream.toByteArray());
                    Map<String, String> metadata = headers.entrySet().stream()
                            .filter(e -> e.getKey().startsWith("x-amz-meta-"))
                            .collect(java.util.stream.Collectors.toMap(
                                    e -> e.getKey().substring("x-amz-meta-".length()),
                                    Map.Entry::getValue
                            ));
                    return storageService.uploadObject(bucket, key, inputStream, metadata);
                })
                .map(ApiResponse::created);
    }

    @GetMapping("/{bucket}/{key}")
    public Mono<ApiResponse<String>> downloadObject(@PathVariable String bucket, @PathVariable String key) {
        return storageService.downloadObject(bucket, key)
                .map(inputStream -> {
                    try {
                        String content = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        return ApiResponse.success(content);
                    } catch (Exception e) {
                        return ApiResponse.<String>error(500, "Failed to read object: " + e.getMessage());
                    }
                });
    }

    @DeleteMapping("/{bucket}/{key}")
    public Mono<ApiResponse<Void>> deleteObject(@PathVariable String bucket, @PathVariable String key) {
        return storageService.deleteObject(bucket, key)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/{bucket}/{key}/metadata")
    public Mono<ApiResponse<Map<String, Object>>> getObjectMetadata(@PathVariable String bucket, @PathVariable String key) {
        return storageService.getObjectMetadata(bucket, key)
                .map(ApiResponse::success);
    }

    @GetMapping("/{bucket}/{key}/presigned-url")
    public Mono<ApiResponse<String>> generatePresignedUrl(
            @PathVariable String bucket,
            @PathVariable String key,
            @RequestParam(defaultValue = "3600") long expirationSeconds) {
        return storageService.generatePresignedUrl(bucket, key, expirationSeconds)
                .map(ApiResponse::success);
    }

    @GetMapping("/{bucket}/objects")
    public Mono<ApiResponse<Flux<Map<String, Object>>>> listObjects(
            @PathVariable String bucket,
            @RequestParam(required = false) String prefix) {
        return Mono.just(ApiResponse.success(storageService.listObjects(bucket, prefix)));
    }

    @GetMapping("/{bucket}")
    public Mono<ApiResponse<Map<String, Object>>> getBucketInfo(@PathVariable String bucket) {
        return storageService.getBucketInfo(bucket)
                .map(ApiResponse::success);
    }
}
