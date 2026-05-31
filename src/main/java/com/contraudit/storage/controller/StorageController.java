package com.contraudit.storage.controller;

import com.contraudit.common.ApiResponse;
import com.contraudit.storage.entity.StoredContent;
import com.contraudit.storage.entity.StorageConfig;
import com.contraudit.storage.entity.StoragePin;
import com.contraudit.storage.service.StorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    @PostMapping("/configs")
    public Mono<ApiResponse<StorageConfig>> createConfig(@Valid @RequestBody StorageConfig config) {
        return Mono.just(ApiResponse.created(storageService.createConfig(config)));
    }

    @GetMapping("/configs/{id}")
    public Mono<ApiResponse<StorageConfig>> getConfig(@PathVariable String id) {
        return Mono.just(ApiResponse.success(storageService.getConfig(id)));
    }

    @GetMapping("/configs")
    public Mono<ApiResponse<List<StorageConfig>>> listConfigs(
            @RequestParam(required = false) String storageType) {
        return Mono.just(ApiResponse.success(storageService.listConfigs(storageType)));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ApiResponse<StoredContent>> uploadFile(
            @RequestPart("file") FilePart file,
            @RequestParam String storageType,
            @RequestParam(required = false) String configId,
            @RequestParam(required = false) String metadata) {
        return file.content()
                .collectList()
                .map(dataBuffers -> {
                    byte[] bytes = dataBuffers.stream()
                            .map(buffer -> {
                                byte[] b = new byte[buffer.readableByteCount()];
                                buffer.read(b);
                                return b;
                            })
                            .reduce((a, b) -> {
                                byte[] combined = new byte[a.length + b.length];
                                System.arraycopy(a, 0, combined, 0, a.length);
                                System.arraycopy(b, 0, combined, a.length, b.length);
                                return combined;
                            })
                            .orElse(new byte[0]);
                    String mimeType = file.headers().getContentType() != null ?
                            file.headers().getContentType().toString() : "application/octet-stream";
                    return storageService.uploadContent(storageType, bytes, mimeType, metadata, configId);
                })
                .map(ApiResponse::created);
    }

    @PostMapping("/upload/text")
    public Mono<ApiResponse<StoredContent>> uploadText(
            @RequestBody Map<String, Object> request) {
        String storageType = (String) request.get("storageType");
        String content = (String) request.get("content");
        String configId = (String) request.get("configId");
        String metadata = (String) request.get("metadata");
        byte[] bytes = content != null ? content.getBytes() : new byte[0];
        return Mono.just(ApiResponse.created(
                storageService.uploadContent(storageType, bytes, "text/plain", metadata, configId)));
    }

    @GetMapping("/contents/{contentId}")
    public Mono<ApiResponse<StoredContent>> getContent(
            @PathVariable String contentId,
            @RequestParam String storageType) {
        return Mono.just(ApiResponse.success(storageService.getContent(contentId, storageType)));
    }

    @GetMapping("/contents")
    public Mono<ApiResponse<List<StoredContent>>> listContents(
            @RequestParam(required = false) String storageType,
            @RequestParam(required = false) String pinStatus) {
        return Mono.just(ApiResponse.success(storageService.listContents(storageType, pinStatus)));
    }

    @GetMapping("/retrieve/{contentId}")
    public Mono<ResponseEntity<Resource>> retrieveContent(
            @PathVariable String contentId,
            @RequestParam String storageType) {
        StoredContent content = storageService.getContent(contentId, storageType);
        byte[] data = storageService.retrieveContent(contentId, storageType);
        ByteArrayResource resource = new ByteArrayResource(data);

        return Mono.just(ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + contentId + "\"")
                .contentType(content.getMimeType() != null ?
                        MediaType.parseMediaType(content.getMimeType()) :
                        MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(data.length)
                .body(resource));
    }

    @PostMapping("/pin/{contentId}")
    public Mono<ApiResponse<StoragePin>> pinContent(
            @PathVariable String contentId,
            @RequestParam String storageType,
            @RequestParam(required = false) String configId) {
        return Mono.just(ApiResponse.success(
                storageService.createPin(contentId, storageType,
                        configId != null ? storageService.getConfig(configId) :
                                storageService.getConfig(storageService.listConfigs(storageType).get(0).getId()))));
    }

    @DeleteMapping("/pin/{contentId}")
    public Mono<ApiResponse<StoragePin>> unpinContent(
            @PathVariable String contentId,
            @RequestParam String storageType) {
        return Mono.just(ApiResponse.success(storageService.unpinContent(contentId, storageType)));
    }

    @GetMapping("/pins")
    public Mono<ApiResponse<List<StoragePin>>> listPins(
            @RequestParam(required = false) String contentId,
            @RequestParam(required = false) String storageType,
            @RequestParam(required = false) String status) {
        return Mono.just(ApiResponse.success(storageService.listPins(contentId, storageType, status)));
    }
}
