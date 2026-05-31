package com.streamsql.feature;

import com.streamsql.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/features")
@RequiredArgsConstructor
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    @GetMapping
    public Mono<ApiResponse<Map<String, Object>>> getAllFeatures() {
        return Mono.just(ApiResponse.success(featureFlagService.getAllFeatureStatus()));
    }

    @GetMapping("/{featureName}")
    public Mono<ApiResponse<Boolean>> isFeatureEnabled(@PathVariable String featureName) {
        return Mono.just(ApiResponse.success(featureFlagService.isEnabled(featureName)));
    }

    @GetMapping("/descriptions")
    public Mono<ApiResponse<Map<String, String>>> getFeatureDescriptions() {
        return Mono.just(ApiResponse.success(featureFlagService.getAllFeatureDescriptions()));
    }
}
