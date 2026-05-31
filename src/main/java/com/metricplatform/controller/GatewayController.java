package com.metricplatform.controller;

import com.metricplatform.common.ApiResponse;
import com.metricplatform.entity.SysApiKey;
import com.metricplatform.entity.SysGatewayRoute;
import com.metricplatform.service.GatewayService;
import com.metricplatform.service.RateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/gateway")
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayService gatewayService;
    private final RateLimitService rateLimitService;

    @GetMapping("/routes")
    public Mono<ApiResponse<List<SysGatewayRoute>>> getAllRoutes() {
        return Mono.just(ApiResponse.success(gatewayService.getAllRoutes()));
    }

    @GetMapping("/routes/{routeId}")
    public Mono<ApiResponse<SysGatewayRoute>> getRoute(@PathVariable String routeId) {
        SysGatewayRoute route = gatewayService.getRouteById(routeId);
        if (route != null) {
            return Mono.just(ApiResponse.success(route));
        } else {
            return Mono.just(ApiResponse.notFound("路由不存在"));
        }
    }

    @PostMapping("/routes")
    public Mono<ApiResponse<SysGatewayRoute>> createRoute(@RequestBody Map<String, Object> request) {
        String path = (String) request.get("path");
        String targetUrl = (String) request.get("targetUrl");
        boolean authRequired = (Boolean) request.getOrDefault("authRequired", true);
        boolean rateLimitEnabled = (Boolean) request.getOrDefault("rateLimitEnabled", true);
        Integer rateLimitCapacity = (Integer) request.get("rateLimitCapacity");
        Integer rateLimitRefill = (Integer) request.get("rateLimitRefill");

        SysGatewayRoute route = gatewayService.createRoute(path, targetUrl, authRequired,
                rateLimitEnabled, rateLimitCapacity, rateLimitRefill);
        return Mono.just(ApiResponse.created(route));
    }

    @PutMapping("/routes/{routeId}")
    public Mono<ApiResponse<SysGatewayRoute>> updateRoute(
            @PathVariable String routeId,
            @RequestBody Map<String, Object> updates) {
        try {
            SysGatewayRoute route = gatewayService.updateRoute(routeId, updates);
            return Mono.just(ApiResponse.success(route));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.notFound(e.getMessage()));
        }
    }

    @DeleteMapping("/routes/{routeId}")
    public Mono<ApiResponse<Void>> deleteRoute(@PathVariable String routeId) {
        boolean result = gatewayService.deleteRoute(routeId);
        if (result) {
            return Mono.just(ApiResponse.success(null));
        } else {
            return Mono.just(ApiResponse.notFound("路由不存在"));
        }
    }

    @GetMapping("/api-keys")
    public Mono<ApiResponse<List<SysApiKey>>> getAllApiKeys() {
        return Mono.just(ApiResponse.success(gatewayService.getAllApiKeys()));
    }

    @GetMapping("/api-keys/{keyId}")
    public Mono<ApiResponse<SysApiKey>> getApiKey(@PathVariable String keyId) {
        SysApiKey apiKey = gatewayService.getApiKeyById(keyId);
        if (apiKey != null) {
            return Mono.just(ApiResponse.success(apiKey));
        } else {
            return Mono.just(ApiResponse.notFound("API Key不存在"));
        }
    }

    @PostMapping("/api-keys")
    public Mono<ApiResponse<SysApiKey>> createApiKey(@RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) request.get("permissions");
        Integer rateLimitCapacity = (Integer) request.getOrDefault("rateLimitCapacity", 1000);
        LocalDateTime expireAt = request.get("expireAt") != null
                ? LocalDateTime.parse((String) request.get("expireAt"))
                : null;

        SysApiKey apiKey = gatewayService.createApiKey(name, permissions, rateLimitCapacity, expireAt);
        return Mono.just(ApiResponse.created(apiKey));
    }

    @PostMapping("/api-keys/{keyId}/revoke")
    public Mono<ApiResponse<Void>> revokeApiKey(@PathVariable String keyId) {
        boolean result = gatewayService.revokeApiKey(keyId);
        if (result) {
            return Mono.just(ApiResponse.success(null));
        } else {
            return Mono.just(ApiResponse.notFound("API Key不存在"));
        }
    }

    @GetMapping("/rate-limit/{key}")
    public Mono<ApiResponse<Map<String, Object>>> getRateLimitStatus(@PathVariable String key) {
        return rateLimitService.getRemaining(key)
                .map(ApiResponse::success);
    }

    @PostMapping("/auth/login")
    public Mono<ApiResponse<Map<String, Object>>> login(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        Map<String, Object> claims = new HashMap<>();
        claims.put("username", username);
        claims.put("role", "admin");

        com.metricplatform.util.JwtUtil jwtUtil = new com.metricplatform.util.JwtUtil();
        String token = jwtUtil.generateToken(username, claims);

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("tokenType", "Bearer");
        result.put("expiresIn", 86400);

        return Mono.just(ApiResponse.success(result));
    }
}
