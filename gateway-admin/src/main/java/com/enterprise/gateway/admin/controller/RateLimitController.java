package com.enterprise.gateway.admin.controller;

import com.enterprise.gateway.admin.service.RateLimitService;
import com.enterprise.gateway.common.model.RateLimitRule;
import com.enterprise.gateway.common.model.UnifiedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin/ratelimit")
@RequiredArgsConstructor
public class RateLimitController {

    private final RateLimitService rateLimitService;

    @GetMapping("/{routeId}")
    public Mono<UnifiedResponse<RateLimitRule>> getByRouteId(@PathVariable String routeId) {
        return Mono.just(UnifiedResponse.success(rateLimitService.getByRouteId(routeId)));
    }

    @PostMapping
    public Mono<UnifiedResponse<RateLimitRule>> createRule(@RequestBody RateLimitRule rule) {
        return Mono.just(UnifiedResponse.success(rateLimitService.createRule(rule)));
    }

    @PutMapping("/{id}")
    public Mono<UnifiedResponse<RateLimitRule>> updateRule(
            @PathVariable Long id,
            @RequestBody RateLimitRule rule) {
        rule.setId(id);
        return Mono.just(UnifiedResponse.success(rateLimitService.updateRule(rule)));
    }

    @DeleteMapping("/{id}")
    public Mono<UnifiedResponse<Void>> deleteRule(@PathVariable Long id) {
        rateLimitService.deleteRule(id);
        return Mono.just(UnifiedResponse.success(null));
    }

    @PostMapping("/toggle/{id}")
    public Mono<UnifiedResponse<RateLimitRule>> toggleRule(@PathVariable Long id) {
        return Mono.just(UnifiedResponse.success(rateLimitService.toggleRule(id)));
    }

    @PostMapping("/refresh")
    public Mono<UnifiedResponse<Void>> refreshAll() {
        rateLimitService.refreshAll();
        return Mono.just(UnifiedResponse.success(null));
    }
}
