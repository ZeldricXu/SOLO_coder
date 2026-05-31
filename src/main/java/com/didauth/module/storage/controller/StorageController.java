package com.didauth.module.storage.controller;

import com.didauth.common.response.ApiResponse;
import com.didauth.core.entity.StorageContent;
import com.didauth.module.storage.dto.StoreContentRequest;
import com.didauth.module.storage.dto.StoreContentResponse;
import com.didauth.module.storage.service.StorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    @PostMapping("/upload")
    public Mono<ApiResponse<StoreContentResponse>> upload(@Valid @RequestBody StoreContentRequest request) {
        return storageService.storeContent(request)
                .map(response -> ApiResponse.success(201, response));
    }

    @GetMapping("/{storageType}/{cid}")
    public Mono<String> retrieve(
            @PathVariable String storageType,
            @PathVariable String cid) {
        return storageService.retrieveContent(cid, storageType);
    }

    @PostMapping("/{storageType}/{cid}/pin")
    public Mono<ApiResponse<String>> pin(
            @PathVariable String storageType,
            @PathVariable String cid) {
        return storageService.pinContent(cid, storageType)
                .map(ApiResponse::success);
    }

    @PostMapping("/{storageType}/{cid}/unpin")
    public Mono<ApiResponse<String>> unpin(
            @PathVariable String storageType,
            @PathVariable String cid) {
        return storageService.unpinContent(cid, storageType)
                .map(ApiResponse::success);
    }

    @GetMapping("/contents")
    public Mono<ApiResponse<List<StorageContent>>> listContents(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String storageType) {
        return storageService.listContents(userId, storageType)
                .map(ApiResponse::success);
    }

    @GetMapping("/contents/{contentId}")
    public Mono<ApiResponse<StorageContent>> getContentInfo(@PathVariable String contentId) {
        return storageService.getContentInfo(contentId)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/contents/{contentId}")
    public Mono<ApiResponse<Void>> deleteContent(@PathVariable String contentId) {
        return storageService.deleteContent(contentId)
                .then(Mono.just(ApiResponse.success(null)));
    }
}
