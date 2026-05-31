package com.chaoslab.modules.traffic.controller;

import com.chaoslab.common.ApiResponse;
import com.chaoslab.entity.TrafficStrategy;
import com.chaoslab.entity.TrafficStrategyRun;
import com.chaoslab.modules.traffic.dto.*;
import com.chaoslab.modules.traffic.service.TrafficControlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/traffic")
@RequiredArgsConstructor
public class TrafficControlController {

    private final TrafficControlService trafficControlService;

    @PostMapping("/strategies")
    public Mono<ApiResponse<TrafficStrategy>> createStrategy(
            @Valid @RequestBody TrafficStrategyCreateRequest request) {
        return trafficControlService.createStrategy(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/strategies")
    public Mono<ApiResponse<List<TrafficStrategy>>> listStrategies(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String namespace) {
        return trafficControlService.listStrategies(type, namespace)
                .map(ApiResponse::success);
    }

    @GetMapping("/strategies/{strategyId}")
    public Mono<ApiResponse<TrafficStrategy>> getStrategy(@PathVariable String strategyId) {
        return trafficControlService.getStrategy(strategyId)
                .map(ApiResponse::success);
    }

    @PostMapping("/canary/start")
    public Mono<ApiResponse<TrafficStrategyRun>> startCanaryRelease(
            @Valid @RequestBody CanaryReleaseRequest request) {
        return trafficControlService.startCanaryRelease(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/canary/{runId}/adjust")
    public Mono<ApiResponse<TrafficStrategyRun>> adjustCanaryTraffic(
            @PathVariable String runId,
            @RequestBody Map<String, Integer> request) {
        int percentage = request.getOrDefault("percentage", 0);
        return trafficControlService.adjustCanaryTraffic(runId, percentage)
                .map(ApiResponse::success);
    }

    @PostMapping("/bluegreen/start")
    public Mono<ApiResponse<TrafficStrategyRun>> startBlueGreenDeploy(
            @Valid @RequestBody BlueGreenDeployRequest request) {
        return trafficControlService.startBlueGreenDeploy(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/bluegreen/{runId}/switch")
    public Mono<ApiResponse<TrafficStrategyRun>> switchBlueGreen(
            @PathVariable String runId,
            @RequestBody Map<String, Boolean> request) {
        boolean toGreen = request.getOrDefault("toGreen", true);
        return trafficControlService.switchBlueGreen(runId, toGreen)
                .map(ApiResponse::success);
    }

    @PostMapping("/circuit/configure")
    public Mono<ApiResponse<TrafficStrategyRun>> configureCircuitBreaker(
            @Valid @RequestBody CircuitBreakerConfigRequest request) {
        return trafficControlService.configureCircuitBreaker(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/mirror/start")
    public Mono<ApiResponse<TrafficStrategyRun>> startTrafficMirror(
            @RequestBody Map<String, String> request) {
        String strategyId = request.get("strategyId");
        String targetService = request.get("targetService");
        return trafficControlService.startTrafficMirror(strategyId, targetService)
                .map(ApiResponse::success);
    }

    @PostMapping("/runs/{runId}/complete")
    public Mono<ApiResponse<TrafficStrategyRun>> completeRun(
            @PathVariable String runId,
            @RequestBody Map<String, Object> request) {
        boolean success = (Boolean) request.getOrDefault("success", true);
        String errorDetail = (String) request.get("errorDetail");
        return trafficControlService.completeStrategyRun(runId, success, errorDetail)
                .map(ApiResponse::success);
    }

    @GetMapping("/runs/{runId}")
    public Mono<ApiResponse<TrafficStrategyRun>> getRun(@PathVariable String runId) {
        return trafficControlService.getStrategyRun(runId)
                .map(ApiResponse::success);
    }
}
