package com.parking.platform.feature.controller;

import com.parking.platform.common.dto.ApiResponse;
import com.parking.platform.feature.dto.EvaluationRequest;
import com.parking.platform.feature.dto.EvaluationResponse;
import com.parking.platform.feature.entity.FeatureFlag;
import com.parking.platform.feature.entity.UserGroup;
import com.parking.platform.feature.service.FeatureFlagService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/feature-flags")
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    public FeatureFlagController(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    @PostMapping
    public ApiResponse<FeatureFlag> createFlag(@RequestBody FeatureFlag flag) {
        return ApiResponse.success(featureFlagService.createFlag(flag));
    }

    @GetMapping("/{id}")
    public ApiResponse<FeatureFlag> getFlag(@PathVariable String id) {
        return ApiResponse.success(featureFlagService.getFlag(id));
    }

    @GetMapping
    public ApiResponse<List<FeatureFlag>> listFlags() {
        return ApiResponse.success(featureFlagService.getAllFlags());
    }

    @PutMapping("/{id}")
    public ApiResponse<FeatureFlag> updateFlag(@PathVariable String id, @RequestBody FeatureFlag flag) {
        return ApiResponse.success(featureFlagService.updateFlag(id, flag));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteFlag(@PathVariable String id) {
        featureFlagService.deleteFlag(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/evaluate")
    public ApiResponse<EvaluationResponse> evaluate(@RequestBody EvaluationRequest request) {
        return ApiResponse.success(featureFlagService.evaluate(request));
    }

    @PostMapping("/groups")
    public ApiResponse<UserGroup> createGroup(@RequestBody UserGroup group) {
        return ApiResponse.success(featureFlagService.createGroup(group));
    }

    @GetMapping("/groups")
    public ApiResponse<List<UserGroup>> listGroups() {
        return ApiResponse.success(featureFlagService.getAllGroups());
    }

    @PutMapping("/groups/{id}")
    public ApiResponse<UserGroup> updateGroup(@PathVariable String id, @RequestBody UserGroup group) {
        return ApiResponse.success(featureFlagService.updateGroup(id, group));
    }

    @DeleteMapping("/groups/{id}")
    public ApiResponse<Void> deleteGroup(@PathVariable String id) {
        featureFlagService.deleteGroup(id);
        return ApiResponse.success(null);
    }
}
