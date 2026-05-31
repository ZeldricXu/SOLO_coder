package com.modelguard.controller;

import com.modelguard.common.ApiResponse;
import com.modelguard.common.PageResult;
import com.modelguard.dto.FeatureLookupDTO;
import com.modelguard.dto.FeatureRegisterDTO;
import com.modelguard.dto.FeatureValueDTO;
import com.modelguard.entity.FeatureRegistry;
import com.modelguard.entity.FeatureValue;
import com.modelguard.service.FeatureStoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/feature")
@RequiredArgsConstructor
public class FeatureStoreController {

    private final FeatureStoreService featureStoreService;

    @PostMapping("/register")
    public Mono<ApiResponse<FeatureRegistry>> registerFeature(@Valid @RequestBody FeatureRegisterDTO dto) {
        return featureStoreService.registerFeature(dto)
                .map(ApiResponse::created);
    }

    @GetMapping("/{featureId}")
    public Mono<ApiResponse<FeatureRegistry>> getFeature(@PathVariable String featureId) {
        return featureStoreService.getFeature(featureId)
                .map(ApiResponse::success);
    }

    @GetMapping("/{featureId}/versions/{version}")
    public Mono<ApiResponse<FeatureRegistry>> getFeatureVersion(
            @PathVariable String featureId,
            @PathVariable Integer version) {
        return featureStoreService.getFeatureVersion(featureId, version)
                .map(ApiResponse::success);
    }

    @GetMapping
    public Mono<ApiResponse<PageResult<FeatureRegistry>>> listFeatures(
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return featureStoreService.pageFeatures(entity, status, pageNum, pageSize)
                .map(ApiResponse::success);
    }

    @PutMapping("/{featureId}")
    public Mono<ApiResponse<FeatureRegistry>> updateFeature(
            @PathVariable String featureId,
            @Valid @RequestBody FeatureRegisterDTO dto) {
        dto.setFeatureId(featureId);
        return featureStoreService.updateFeature(featureId, dto)
                .map(ApiResponse::success);
    }

    @GetMapping("/entity/{entity}")
    public Mono<ApiResponse<List<FeatureRegistry>>> listEntityFeatures(@PathVariable String entity) {
        return featureStoreService.listEntityFeatures(entity)
                .map(ApiResponse::success);
    }

    @PostMapping("/values")
    public Mono<ApiResponse<FeatureValue>> putFeatureValue(@Valid @RequestBody FeatureValueDTO dto) {
        return featureStoreService.putFeatureValue(dto)
                .map(ApiResponse::created);
    }

    @PostMapping("/values/batch")
    public Mono<ApiResponse<Boolean>> batchPutFeatureValues(@Valid @RequestBody List<FeatureValueDTO> values) {
        return featureStoreService.batchPutFeatureValues(values)
                .map(ApiResponse::success);
    }

    @PostMapping("/lookup")
    public Mono<ApiResponse<Map<String, Object>>> getFeatureValues(@Valid @RequestBody FeatureLookupDTO dto) {
        return featureStoreService.getFeatureValues(dto)
                .map(ApiResponse::success);
    }

    @GetMapping("/{featureId}/values/{entityId}/latest")
    public Mono<ApiResponse<FeatureValue>> getLatestFeatureValue(
            @PathVariable String featureId,
            @PathVariable String entityId) {
        return featureStoreService.getLatestFeatureValue(featureId, entityId)
                .map(ApiResponse::success);
    }

    @GetMapping("/{featureId}/values/{entityId}/history")
    public Mono<ApiResponse<List<FeatureValue>>> getFeatureValueHistory(
            @PathVariable String featureId,
            @PathVariable String entityId,
            @RequestParam(required = false) LocalDateTime startTime,
            @RequestParam(required = false) LocalDateTime endTime) {
        return featureStoreService.getFeatureValueHistory(featureId, entityId, startTime, endTime)
                .map(ApiResponse::success);
    }

    @PostMapping("/offline/lookup")
    public Mono<ApiResponse<Map<String, Object>>> getOfflineFeatures(
            @RequestParam String entityId,
            @RequestParam List<String> featureIds,
            @RequestParam(required = false) LocalDateTime asOfTime) {
        return featureStoreService.getOfflineFeatures(entityId, featureIds, asOfTime)
                .map(ApiResponse::success);
    }

    @PostMapping("/{featureId}/values/{entityId}/sync")
    public Mono<ApiResponse<Boolean>> syncOfflineToOnline(
            @PathVariable String featureId,
            @PathVariable String entityId) {
        return featureStoreService.syncOfflineToOnline(featureId, entityId)
                .map(ApiResponse::success);
    }

    @GetMapping("/{featureId}/values/{entityId}/validate")
    public Mono<ApiResponse<Boolean>> validateOnlineOfflineConsistency(
            @PathVariable String featureId,
            @PathVariable String entityId) {
        return featureStoreService.validateOnlineOfflineConsistency(featureId, entityId)
                .map(ApiResponse::success);
    }

    @GetMapping("/{featureId}/consistency")
    public Mono<ApiResponse<Map<String, Object>>> checkConsistency(@PathVariable String featureId) {
        return featureStoreService.checkConsistency(featureId)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/{featureId}/values")
    public Mono<ApiResponse<Void>> deleteFeatureValues(
            @PathVariable String featureId,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) LocalDateTime beforeTime) {
        return featureStoreService.deleteFeatureValues(featureId, entityId, beforeTime)
                .then(Mono.just(ApiResponse.success()));
    }

    @GetMapping("/{featureId}/stats")
    public Mono<ApiResponse<Map<String, Object>>> getFeatureStats(@PathVariable String featureId) {
        return featureStoreService.getFeatureStats(featureId)
                .map(ApiResponse::success);
    }
}
