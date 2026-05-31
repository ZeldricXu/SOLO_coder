package com.solocoder.presentation.controller;

import com.solocoder.application.service.FeatureStoreService;
import com.solocoder.domain.model.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/features")
@RequiredArgsConstructor
public class FeatureStoreController {

    private final FeatureStoreService featureStoreService;

    @PostMapping("/register")
    public Mono<ApiResponse<Void>> registerFeature(
            @RequestBody @Valid Map<String, Object> request) {
        String featureName = (String) request.get("featureName");
        String description = (String) request.getOrDefault("description", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) request.get("schema");
        return featureStoreService.registerFeature(featureName, description, schema);
    }

    @GetMapping("/online/{entityId}")
    public Mono<ApiResponse<Map<String, Object>>> getOnlineFeatures(
            @PathVariable String entityId,
            @RequestParam @NotEmpty List<String> featureNames) {
        return featureStoreService.getOnlineFeatures(entityId, featureNames);
    }

    @GetMapping("/offline/{entityId}")
    public Mono<ApiResponse<Flux<Map<String, Object>>>> getOfflineFeatures(
            @PathVariable String entityId,
            @RequestParam @NotEmpty List<String> featureNames,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime) {
        return featureStoreService.getOfflineFeatures(entityId, featureNames, startTime, endTime);
    }

    @PostMapping("/ingest")
    public Mono<ApiResponse<Void>> ingestFeatures(
            @RequestBody @Valid Map<String, Object> request) {
        String entityId = (String) request.get("entityId");
        @SuppressWarnings("unchecked")
        Map<String, Object> features = (Map<String, Object>) request.get("features");
        Instant eventTime = request.containsKey("eventTime")
                ? Instant.parse((String) request.get("eventTime"))
                : Instant.now();
        return featureStoreService.ingestFeatures(entityId, features, eventTime);
    }

    @GetMapping("/consistency/{entityId}/{featureName}")
    public Mono<ApiResponse<Boolean>> checkConsistency(
            @PathVariable String entityId,
            @PathVariable String featureName) {
        return featureStoreService.checkConsistency(entityId, featureName);
    }

    @PostMapping("/sync/{featureName}")
    public Mono<ApiResponse<Void>> syncOnlineToOffline(
            @PathVariable String featureName) {
        return featureStoreService.syncOnlineToOffline(featureName);
    }
}
