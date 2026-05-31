package com.streamsql.resilience;

import com.streamsql.common.ApiResponse;
import com.streamsql.feature.FeatureFlagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/resilience")
@RequiredArgsConstructor
public class ResilienceController {

    private final ResilienceService resilienceService;
    private final FeatureFlagService featureFlagService;

    @GetMapping("/config")
    public Mono<ApiResponse<ResilienceConfig>> getConfig() {
        return Mono.just(ApiResponse.success(resilienceService.getConfig()));
    }

    @GetMapping("/circuit-breakers")
    public Mono<ApiResponse<Map<String, CircuitBreaker.Metrics>>> getCircuitBreakers() {
        return featureFlagService.executeWithFeature(
                "circuit-breaker",
                () -> ApiResponse.success(resilienceService.getCircuitBreakerMetrics()),
                () -> ApiResponse.error(400, "Circuit breaker feature is disabled")
        );
    }

    @PostMapping("/circuit-breakers/{name}/reset")
    public Mono<ApiResponse<Void>> resetCircuitBreaker(@PathVariable String name) {
        resilienceService.resetCircuitBreaker(name);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/circuit-breakers/reset-all")
    public Mono<ApiResponse<Void>> resetAllCircuitBreakers() {
        resilienceService.resetAllCircuitBreakers();
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/test")
    public Mono<ApiResponse<Map<String, Object>>> testResilience(
            @RequestBody Map<String, Object> request,
            @RequestParam(defaultValue = "circuit-breaker") String mode) {

        return featureFlagService.executeWithFeature(
                mode,
                () -> {
                    boolean shouldFail = (boolean) request.getOrDefault("shouldFail", false);
                    String operationName = (String) request.getOrDefault("operationName", "test");

                    Map<String, Object> result = new HashMap<>();

                    resilienceService.executeWithCircuitBreaker(
                            operationName,
                            () -> {
                                if (shouldFail) {
                                    throw new RuntimeException("Simulated failure");
                                }
                                result.put("status", "success");
                                result.put("message", "Operation completed successfully");
                                return null;
                            },
                            () -> {
                                result.put("status", "fallback");
                                result.put("message", "Fallback executed");
                                return null;
                            }
                    );

                    return ApiResponse.success(result);
                },
                () -> ApiResponse.error(400, "Resilience feature is disabled")
        );
    }

    @GetMapping("/features")
    public Mono<ApiResponse<Map<String, Boolean>>> getFeatures() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("circuitBreaker", featureFlagService.isEnabled("circuit-breaker"));
        features.put("retryPolicy", featureFlagService.isEnabled("retry-policy"));
        features.put("fallbackPolicy", featureFlagService.isEnabled("fallback-policy"));
        return Mono.just(ApiResponse.success(features));
    }
}
