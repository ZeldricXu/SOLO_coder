package com.enterprise.gateway.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.gateway.admin.service.RouteService;
import com.enterprise.gateway.common.model.RouteDefinition;
import com.enterprise.gateway.common.model.UnifiedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin/routes")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;

    @GetMapping
    public Mono<UnifiedResponse<Page<RouteDefinition>>> listRoutes(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Mono.just(UnifiedResponse.success(routeService.listRoutes(page, size)));
    }

    @GetMapping("/{id}")
    public Mono<UnifiedResponse<RouteDefinition>> getRouteById(@PathVariable Long id) {
        return Mono.just(UnifiedResponse.success(routeService.getRouteById(id)));
    }

    @PostMapping
    public Mono<UnifiedResponse<RouteDefinition>> createRoute(@RequestBody RouteDefinition entity) {
        return Mono.just(UnifiedResponse.success(routeService.createRoute(entity)));
    }

    @PutMapping("/{id}")
    public Mono<UnifiedResponse<RouteDefinition>> updateRoute(
            @PathVariable Long id,
            @RequestBody RouteDefinition entity) {
        entity.setId(id);
        return Mono.just(UnifiedResponse.success(routeService.updateRoute(entity)));
    }

    @DeleteMapping("/{id}")
    public Mono<UnifiedResponse<Void>> deleteRoute(@PathVariable Long id) {
        routeService.deleteRoute(id);
        return Mono.just(UnifiedResponse.success(null));
    }

    @PostMapping("/refresh")
    public Mono<UnifiedResponse<Void>> refreshRoutes() {
        routeService.refreshAll();
        return Mono.just(UnifiedResponse.success(null));
    }

    @PostMapping("/{id}/status")
    public Mono<UnifiedResponse<RouteDefinition>> enableRoute(
            @PathVariable Long id,
            @RequestParam Integer status) {
        return Mono.just(UnifiedResponse.success(routeService.enableRoute(id, status)));
    }
}
