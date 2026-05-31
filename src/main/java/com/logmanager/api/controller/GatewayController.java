package com.logmanager.api.controller;

import com.logmanager.api.vo.ApiResponse;
import com.logmanager.gateway.ApiGatewayService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/gateway")
@RequiredArgsConstructor
public class GatewayController {

    private final ApiGatewayService apiGatewayService;

    @PostMapping("/route")
    public Mono<ServerResponse> routeRequest(ServerRequest request) {
        return apiGatewayService.routeRequest(request);
    }

    @PostMapping("/routes")
    public Mono<ApiResponse<Void>> registerRoute(
            @RequestParam String path,
            @RequestParam String targetService,
            @RequestParam String method) {
        apiGatewayService.registerRoute(path, targetService, method);
        return Mono.just(ApiResponse.success(null));
    }

    @DeleteMapping("/routes/{path}")
    public Mono<ApiResponse<Void>> removeRoute(@PathVariable String path) {
        apiGatewayService.removeRoute(path);
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/routes")
    public Mono<ApiResponse<Map<String, ApiGatewayService.RouteInfo>>> getRoutes() {
        return Mono.just(ApiResponse.success(apiGatewayService.getRoutes()));
    }

    @GetMapping("/metrics")
    public Mono<ApiResponse<Map<String, Object>>> getGatewayMetrics() {
        return apiGatewayService.getGatewayMetrics()
                .map(ApiResponse::success);
    }
}
