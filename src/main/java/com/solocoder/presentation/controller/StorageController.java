package com.solocoder.presentation.controller;

import com.solocoder.application.service.StorageService;
import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.model.CoreEntity;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    @PostMapping("/files")
    public Mono<ApiResponse<Map<String, Object>>> uploadFile(
            @RequestPart("file") Mono<FilePart> filePartMono,
            @RequestHeader Map<String, String> headers) {

        return filePartMono.flatMap(filePart -> {
            String fileName = filePart.filename();
            Map<String, String> metadata = extractMetadata(headers);

            return filePart.content()
                    .reduce(new InputStream[1], (is, dataBuffer) -> {
                        if (is[0] == null) {
                            is[0] = dataBuffer.asInputStream();
                        }
                        return is;
                    })
                    .flatMap(is -> storageService.storeFile(fileName, is[0], 0, metadata));
        });
    }

    @GetMapping("/files/{id}")
    public Mono<ApiResponse<CoreEntity>> getFileMetadata(@PathVariable String id) {
        return storageService.getFileMetadata(id);
    }

    @DeleteMapping("/files/{id}")
    public Mono<ApiResponse<Boolean>> deleteFile(@PathVariable String id) {
        return storageService.deleteFile(id);
    }

    @PostMapping("/files/{id}/lifecycle")
    public Mono<ApiResponse<Void>> applyLifecyclePolicy(
            @PathVariable String id,
            @RequestParam @NotBlank String policyName) {
        return storageService.applyLifecyclePolicy(id, policyName);
    }

    @GetMapping("/files")
    public Mono<ApiResponse<Flux<CoreEntity>>> listFiles(
            @RequestParam(required = false) String prefix,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return storageService.listFiles(prefix, page, size);
    }

    private Map<String, String> extractMetadata(Map<String, String> headers) {
        Map<String, String> metadata = new HashMap<>();
        headers.forEach((key, value) -> {
            if (key.toLowerCase().startsWith("x-meta-")) {
                metadata.put(key.substring(7).toLowerCase(), value);
            }
        });
        return metadata;
    }
}
