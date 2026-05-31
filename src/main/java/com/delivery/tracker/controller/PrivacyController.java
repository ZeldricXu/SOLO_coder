package com.delivery.tracker.controller;

import com.delivery.tracker.common.Result;
import com.delivery.tracker.entity.PrivacyBudget;
import com.delivery.tracker.privacy.PrivacyConfig;
import com.delivery.tracker.service.DifferentialPrivacyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/privacy")
@RequiredArgsConstructor
public class PrivacyController {

    private final DifferentialPrivacyService privacyService;

    @PostMapping("/apply")
    public Mono<Result<Map<String, Object>>> applyPrivacy(@RequestBody Map<String, Object> request) {
        String userId = (String) request.get("userId");
        @SuppressWarnings("unchecked")
        Map<String, Object> queryResult = (Map<String, Object>) request.get("queryResult");
        double sensitivity = ((Number) request.getOrDefault("sensitivity", 1.0)).doubleValue();

        return privacyService.applyDifferentialPrivacy(userId, queryResult, sensitivity)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    @PostMapping("/apply/scene")
    public Mono<Result<Map<String, Object>>> applyPrivacyByScene(@RequestBody Map<String, Object> request) {
        String userId = (String) request.get("userId");
        @SuppressWarnings("unchecked")
        Map<String, Object> queryResult = (Map<String, Object>) request.get("queryResult");
        String scene = (String) request.getOrDefault("scene", "DEFAULT");

        return privacyService.applyDifferentialPrivacyWithScene(userId, queryResult, scene)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    @PostMapping("/apply/config")
    public Mono<Result<Map<String, Object>>> applyPrivacyByConfig(@RequestBody Map<String, Object> request) {
        String userId = (String) request.get("userId");
        @SuppressWarnings("unchecked")
        Map<String, Object> queryResult = (Map<String, Object>) request.get("queryResult");
        String configId = (String) request.get("configId");

        return privacyService.applyDifferentialPrivacyWithConfig(userId, queryResult, configId)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    @PostMapping("/apply/custom")
    public Mono<Result<Map<String, Object>>> applyPrivacyCustom(@RequestBody Map<String, Object> request) {
        String userId = (String) request.get("userId");
        @SuppressWarnings("unchecked")
        Map<String, Object> queryResult = (Map<String, Object>) request.get("queryResult");
        String strategyName = (String) request.getOrDefault("strategyName", "LAPLACE");
        double epsilon = ((Number) request.getOrDefault("epsilon", 0.1)).doubleValue();
        double delta = ((Number) request.getOrDefault("delta", 0.00001)).doubleValue();
        double sensitivity = ((Number) request.getOrDefault("sensitivity", 1.0)).doubleValue();

        return privacyService.applyDifferentialPrivacyCustom(userId, queryResult, strategyName, epsilon, delta, sensitivity)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    @GetMapping("/budget/{userId}")
    public Mono<Result<PrivacyBudget>> getPrivacyBudget(@PathVariable String userId) {
        return privacyService.getPrivacyBudget(userId)
                .map(Result::success);
    }

    @PostMapping("/budget/{userId}/reset")
    public Mono<Result<PrivacyBudget>> resetPrivacyBudget(@PathVariable String userId) {
        return privacyService.resetPrivacyBudget(userId)
                .map(Result::success);
    }

    @GetMapping("/strategies")
    public Mono<Result<List<String>>> getAvailableStrategies() {
        return privacyService.getAvailableStrategies()
                .map(Result::success);
    }

    @GetMapping("/configs")
    public Mono<Result<Map<String, PrivacyConfig>>> getAllConfigs() {
        return privacyService.getAllConfigs()
                .map(Result::success);
    }

    @PostMapping("/configs")
    public Mono<Result<Void>> addConfig(@RequestBody PrivacyConfig config) {
        return privacyService.addConfig(config)
                .then(Mono.just(Result.success()));
    }

    @PutMapping("/configs/{configId}")
    public Mono<Result<Void>> updateConfig(@PathVariable String configId, @RequestBody PrivacyConfig config) {
        return privacyService.updateConfig(configId, config)
                .then(Mono.just(Result.success()));
    }
}
