package com.solocoder.platform.storage.adapter.rest;

import com.solocoder.platform.persistence.common.ApiResponse;
import com.solocoder.platform.storage.adapter.dto.StorageRequestDto;
import com.solocoder.platform.storage.adapter.dto.StorageResponseDto;
import com.solocoder.platform.storage.application.service.StorageApplicationService;
import com.solocoder.platform.storage.domain.model.StoredContent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageApplicationService storageApplicationService;

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<StorageResponseDto>> upload(
            @Valid @RequestBody StorageRequestDto request) {
        StoredContent content = storageApplicationService.upload(
                request.getContent(),
                request.getContentType(),
                request.getStorageType(),
                request.getNetwork(),
                request.getPin(),
                request.getPinLocation(),
                request.getMetadata(),
                request.getCreatedBy());
        return ResponseEntity.ok(ApiResponse.success(toResponseDto(content)));
    }

    @GetMapping("/{contentId}")
    public ResponseEntity<ApiResponse<StorageResponseDto>> getContentInfo(
            @PathVariable String contentId) {
        StoredContent content = storageApplicationService.getContentInfo(contentId);
        return ResponseEntity.ok(ApiResponse.success(toResponseDto(content)));
    }

    @GetMapping("/{contentId}/data")
    public ResponseEntity<byte[]> getContent(@PathVariable String contentId) {
        byte[] data = storageApplicationService.getContent(contentId);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/{contentId}/gateway")
    public ResponseEntity<ApiResponse<String>> getGatewayUrl(@PathVariable String contentId) {
        String url = storageApplicationService.getGatewayUrl(contentId);
        return ResponseEntity.ok(ApiResponse.success(url));
    }

    @PostMapping("/{contentId}/pin")
    public ResponseEntity<ApiResponse<Boolean>> pinContent(
            @PathVariable String contentId,
            @RequestParam(required = false) String location) {
        boolean result = storageApplicationService.pinContent(contentId, location);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{contentId}/unpin")
    public ResponseEntity<ApiResponse<Boolean>> unpinContent(@PathVariable String contentId) {
        boolean result = storageApplicationService.unpinContent(contentId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @DeleteMapping("/{contentId}")
    public ResponseEntity<ApiResponse<Boolean>> deleteContent(@PathVariable String contentId) {
        boolean result = storageApplicationService.deleteContent(contentId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/type/{storageType}")
    public ResponseEntity<ApiResponse<List<StorageResponseDto>>> findByStorageType(
            @PathVariable String storageType,
            @RequestParam(defaultValue = "10") int limit) {
        List<StoredContent> contents = storageApplicationService.findByStorageType(storageType, limit);
        List<StorageResponseDto> dtos = contents.stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/pin-status/{pinStatus}")
    public ResponseEntity<ApiResponse<List<StorageResponseDto>>> findByPinStatus(
            @PathVariable String pinStatus,
            @RequestParam(defaultValue = "10") int limit) {
        List<StoredContent> contents = storageApplicationService.findByPinStatus(pinStatus, limit);
        List<StorageResponseDto> dtos = contents.stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    private StorageResponseDto toResponseDto(StoredContent content) {
        return StorageResponseDto.builder()
                .contentId(content.getContentId())
                .contentHash(content.getContentHash())
                .storageType(content.getStorageType() != null ? content.getStorageType().name() : null)
                .network(content.getNetwork())
                .size(content.getSize())
                .mimeType(content.getMimeType())
                .pinStatus(content.getPinStatus() != null ? content.getPinStatus().name() : null)
                .pinLocation(content.getPinLocation())
                .replicationCount(content.getReplicationCount())
                .expireTime(content.getExpireTime())
                .metadata(content.getMetadata())
                .gatewayUrl(storageApplicationService.getGatewayUrl(content.getContentId()))
                .createdBy(content.getCreatedBy())
                .createdAt(content.getCreatedAt())
                .build();
    }
}
