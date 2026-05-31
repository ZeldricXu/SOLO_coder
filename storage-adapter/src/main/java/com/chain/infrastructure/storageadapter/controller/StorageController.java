package com.chain.infrastructure.storageadapter.controller;

import com.chain.infrastructure.common.dto.ApiResponse;
import com.chain.infrastructure.persistence.entity.StorageObject;
import com.chain.infrastructure.storageadapter.dto.StoreRequest;
import com.chain.infrastructure.storageadapter.dto.StoreResult;
import com.chain.infrastructure.storageadapter.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    @PostMapping("/store")
    public Mono<ApiResponse<StoreResult>> store(@RequestBody StoreRequest request) {
        return storageService.store(request)
                .map(ApiResponse::created);
    }

    @GetMapping("/{storageNetwork}/{cid}")
    public Mono<ResponseEntity<Resource>> retrieve(
            @PathVariable String storageNetwork,
            @PathVariable String cid) {
        return storageService.retrieve(storageNetwork, cid)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(new ByteArrayResource(bytes)));
    }

    @PostMapping("/{storageNetwork}/{cid}/pin")
    public Mono<ApiResponse<Boolean>> pin(
            @PathVariable String storageNetwork,
            @PathVariable String cid) {
        return storageService.pin(storageNetwork, cid)
                .map(ApiResponse::success);
    }

    @PostMapping("/{storageNetwork}/{cid}/unpin")
    public Mono<ApiResponse<Boolean>> unpin(
            @PathVariable String storageNetwork,
            @PathVariable String cid) {
        return storageService.unpin(storageNetwork, cid)
                .map(ApiResponse::success);
    }

    @GetMapping("/objects/{objectId}")
    public Mono<ApiResponse<StorageObject>> getObject(@PathVariable String objectId) {
        return storageService.getObject(objectId)
                .map(ApiResponse::success);
    }

    @GetMapping("/objects/{storageNetwork}/cid/{cid}")
    public Mono<ApiResponse<StorageObject>> getObjectByCid(
            @PathVariable String storageNetwork,
            @PathVariable String cid) {
        return storageService.getObjectByCid(storageNetwork, cid)
                .map(ApiResponse::success);
    }
}
