package com.solo.config.controller;

import com.solo.config.common.Result;
import com.solo.config.entity.FlowPolicy;
import com.solo.config.module.flow.FlowControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/flow")
@RequiredArgsConstructor
public class FlowController {

    private final FlowControlService flowControlService;

    @GetMapping("/canary/{serviceName}")
    public Mono<Result<Map<String, Object>>> routeCanary(
            @PathVariable String serviceName,
            @RequestHeader Map<String, String> headers) {
        return flowControlService.routeCanary(serviceName, headers)
                .map(route -> Result.success(Map.of("route", route, "service", serviceName)));
    }

    @GetMapping("/bluegreen/{serviceName}")
    public Mono<Result<Map<String, Object>>> routeBlueGreen(@PathVariable String serviceName) {
        return flowControlService.routeBlueGreen(serviceName)
                .map(route -> Result.success(Map.of("route", route, "service", serviceName)));
    }

    @GetMapping("/mirror/{serviceName}")
    public Mono<Result<Map<String, Object>>> shouldMirror(@PathVariable String serviceName) {
        return flowControlService.shouldMirror(serviceName)
                .map(should -> Result.success(Map.of("mirror", should, "service", serviceName)));
    }

    @GetMapping("/circuit-breaker/{serviceName}")
    public Mono<Result<Map<String, Object>>> getCircuitBreakerStatus(@PathVariable String serviceName) {
        return flowControlService.getCircuitBreakerStatus(serviceName)
                .map(Result::success);
    }

    @PostMapping("/circuit-breaker/{serviceName}/success")
    public Mono<Result<Void>> recordSuccess(@PathVariable String serviceName) {
        flowControlService.recordSuccess(serviceName);
        return Mono.just(Result.success());
    }

    @PostMapping("/circuit-breaker/{serviceName}/failure")
    public Mono<Result<Void>> recordFailure(@PathVariable String serviceName) {
        flowControlService.recordFailure(serviceName);
        return Mono.just(Result.success());
    }

    @PostMapping("/policies")
    public Mono<Result<FlowPolicy>> createPolicy(@RequestBody FlowPolicy policy) {
        return flowControlService.createPolicy(policy)
                .map(Result::success);
    }

    @GetMapping("/policies")
    public Flux<FlowPolicy> listPolicies(@RequestParam(required = false) String type) {
        return flowControlService.listPolicies(type);
    }

    @GetMapping("/policies/{policyId}")
    public Mono<Result<FlowPolicy>> getPolicy(@PathVariable String policyId) {
        return flowControlService.getPolicy(policyId)
                .map(Result::success)
                .defaultIfEmpty(Result.error(404, "策略不存在"));
    }

    @PutMapping("/policies/{policyId}")
    public Mono<Result<FlowPolicy>> updatePolicy(
            @PathVariable String policyId,
            @RequestBody FlowPolicy policy) {
        return flowControlService.updatePolicy(policyId, policy)
                .map(Result::success);
    }

    @DeleteMapping("/policies/{policyId}")
    public Mono<Result<Void>> deletePolicy(@PathVariable String policyId) {
        return flowControlService.deletePolicy(policyId)
                .then(Mono.just(Result.success()));
    }
}
