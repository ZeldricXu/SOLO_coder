package com.solocoder.platform.featurestore.controller;

import com.solocoder.platform.common.model.ApiResponse;
import com.solocoder.platform.featurestore.model.*;
import com.solocoder.platform.featurestore.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/features")
@RequiredArgsConstructor
public class FeatureStoreController {

    private final FeatureRegistry featureRegistry;
    private final OnlineFeatureService onlineService;
    private final OfflineFeatureService offlineService;
    private final ConsistencyValidator consistencyValidator;

    @PostMapping("/registry")
    public ApiResponse<FeatureDefinition> register(@Valid @RequestBody FeatureDefinition definition) {
        return ApiResponse.success(featureRegistry.register(definition));
    }

    @GetMapping("/registry/{featureId}")
    public ApiResponse<FeatureDefinition> getFeature(@PathVariable String featureId) {
        return featureRegistry.getFeature(featureId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Feature not found: " + featureId));
    }

    @GetMapping("/registry")
    public ApiResponse<List<FeatureDefinition>> listFeatures() {
        return ApiResponse.success(featureRegistry.listFeatures());
    }

    @PutMapping("/registry")
    public ApiResponse<FeatureDefinition> updateFeature(@Valid @RequestBody FeatureDefinition definition) {
        return ApiResponse.success(featureRegistry.updateFeature(definition));
    }

    @DeleteMapping("/registry/{featureId}")
    public ApiResponse<Void> deleteFeature(@PathVariable String featureId) {
        featureRegistry.deleteFeature(featureId);
        return ApiResponse.success();
    }

    @PostMapping("/online")
    public ApiResponse<Void> putOnline(@RequestBody FeatureValue value) {
        onlineService.put(value);
        return ApiResponse.success();
    }

    @GetMapping("/online/{featureId}/{entityId}")
    public ApiResponse<FeatureValue> getOnline(@PathVariable String featureId, @PathVariable String entityId) {
        return onlineService.get(featureId, entityId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Feature value not found"));
    }

    @PostMapping("/online/batch")
    public ApiResponse<Map<String, FeatureValue>> getOnlineBatch(@RequestParam String entityId,
                                                                  @RequestBody List<String> featureIds) {
        return ApiResponse.success(onlineService.getBatch(entityId, featureIds));
    }

    @PostMapping("/offline")
    public ApiResponse<Void> storeOffline(@RequestBody FeatureValue value) {
        offlineService.store(value);
        return ApiResponse.success();
    }

    @GetMapping("/offline/{featureId}/{entityId}")
    public ApiResponse<FeatureValue> queryOffline(@PathVariable String featureId,
                                                   @PathVariable String entityId,
                                                   @RequestParam long timestamp) {
        return offlineService.query(featureId, entityId, timestamp)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Feature value not found"));
    }

    @PostMapping("/consistency/{featureId}")
    public ApiResponse<ConsistencyReport> validateConsistency(@PathVariable String featureId,
                                                              @RequestBody List<String> entityIds) {
        return ApiResponse.success(consistencyValidator.validate(featureId, entityIds));
    }
}
