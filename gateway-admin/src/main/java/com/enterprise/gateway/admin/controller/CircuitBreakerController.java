package com.enterprise.gateway.admin.controller;

import com.enterprise.gateway.admin.service.CircuitBreakerService;
import com.enterprise.gateway.common.model.CircuitBreakerRule;
import com.enterprise.gateway.common.model.UnifiedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin/circuitbreaker")
@RequiredArgsConstructor
public class CircuitBreakerController {

    private final CircuitBreakerService circuitBreakerService;

    @GetMapping("/{routeId}")
    public Mono<UnifiedResponse<CircuitBreakerRule>> getByRouteId(@PathVariable String routeId) {
        return Mono.just(UnifiedResponse.success(circuitBreakerService.getByRouteId(routeId)));
    }

    @PostMapping
    public Mono<UnifiedResponse<CircuitBreakerRule>> createRule(@RequestBody CircuitBreakerRule rule) {
        return Mono.just(UnifiedResponse.success(circuitBreakerService.createOrUpdate(rule)));
    }

    @PutMapping("/{id}")
    public Mono<UnifiedResponse<CircuitBreakerRule>> updateRule(
            @PathVariable Long id,
            @RequestBody CircuitBreakerRule rule) {
        rule.setId(id);
        return Mono.just(UnifiedResponse.success(circuitBreakerService.createOrUpdate(rule)));
    }

    @DeleteMapping("/{id}")
    public Mono<UnifiedResponse<Void>> deleteRule(@PathVariable Long id) {
        circuitBreakerService.deleteRule(id);
        return Mono.just(UnifiedResponse.success(null));
    }

    @GetMapping("/state/{routeId}")
    public Mono<UnifiedResponse<String>> getState(@PathVariable String routeId) {
        return Mono.just(UnifiedResponse.success(circuitBreakerService.getState(routeId)));
    }

    @PostMapping("/reset/{routeId}")
    public Mono<UnifiedResponse<Void>> resetCircuitBreaker(@PathVariable String routeId) {
        circuitBreakerService.resetCircuitBreaker(routeId);
        return Mono.just(UnifiedResponse.success(null));
    }
}
