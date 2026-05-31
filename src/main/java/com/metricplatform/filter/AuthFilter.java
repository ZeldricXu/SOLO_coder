package com.metricplatform.filter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metricplatform.common.ApiResponse;
import com.metricplatform.entity.SysApiKey;
import com.metricplatform.entity.SysGatewayRoute;
import com.metricplatform.event.GatewayEventPublisher;
import com.metricplatform.mapper.SysApiKeyMapper;
import com.metricplatform.mapper.SysGatewayRouteMapper;
import com.metricplatform.service.RateLimitService;
import com.metricplatform.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class AuthFilter implements WebFilter {

    private final JwtUtil jwtUtil;
    private final RateLimitService rateLimitService;
    private final SysApiKeyMapper apiKeyMapper;
    private final SysGatewayRouteMapper routeMapper;
    private final ObjectMapper objectMapper;
    private final GatewayEventPublisher eventPublisher;

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/actuator",
            "/swagger",
            "/v3/api-docs"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();

        String clientIp = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        SysGatewayRoute route = findRoute(path);
        if (route != null && !route.getEnabled()) {
            eventPublisher.publishAuthFailure(clientIp, path, method, "路由已禁用: " + route.getPath(), null);
            return writeErrorResponse(exchange, HttpStatus.SERVICE_UNAVAILABLE, "路由已禁用");
        }

        if (route != null && route.getRateLimitEnabled()) {
            int capacity = route.getRateLimitCapacity() != null ? route.getRateLimitCapacity() : 100;
            int refill = route.getRateLimitRefill() != null ? route.getRateLimitRefill() : 10;

            return rateLimitService.tryAcquire("ip:" + clientIp, capacity, refill, java.time.Duration.ofSeconds(1))
                    .flatMap(allowed -> {
                        if (!allowed) {
                            log.warn("IP限流触发: {} - {}", clientIp, path);
                            eventPublisher.publishRateLimitTriggered(clientIp, path, method, null,
                                    Map.of("limitType", "IP", "capacity", capacity, "refill", refill));
                            return writeErrorResponse(exchange, HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
                        }
                        return proceedWithAuth(exchange, chain, route, clientIp);
                    });
        }

        return proceedWithAuth(exchange, chain, route, clientIp);
    }

    private Mono<Void> proceedWithAuth(ServerWebExchange exchange, WebFilterChain chain, SysGatewayRoute route, String clientIp) {
        String path = exchange.getRequest().getPath().value();

        if (route == null || route.getAuthRequired()) {
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            String apiKeyHeader = exchange.getRequest().getHeaders().getFirst("X-API-Key");

            if (apiKeyHeader != null && !apiKeyHeader.isEmpty()) {
                return authenticateByApiKey(exchange, chain, apiKeyHeader, clientIp);
            } else if (authHeader != null && authHeader.startsWith("Bearer ")) {
                return authenticateByJwt(exchange, chain, authHeader.substring(7), clientIp, method, path);
            } else {
                eventPublisher.publishAuthFailure(clientIp, path, method, "缺少认证凭证", null);
                return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "缺少认证凭证");
            }
        }

        return chain.filter(exchange);
    }

    private Mono<Void> authenticateByApiKey(ServerWebExchange exchange, WebFilterChain chain, String apiKey, String clientIp) {
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();

        SysApiKey apiKeyEntity = apiKeyMapper.selectOne(new LambdaQueryWrapper<SysApiKey>()
                .eq(SysApiKey::getApiKey, apiKey)
                .eq(SysApiKey::getStatus, "active"));

        if (apiKeyEntity == null) {
            eventPublisher.publishAuthFailure(clientIp, path, method, "无效的API Key",
                    Map.of("apiKey", maskApiKey(apiKey)));
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "无效的API Key");
        }

        if (apiKeyEntity.getExpireAt() != null && apiKeyEntity.getExpireAt().isBefore(LocalDateTime.now())) {
            eventPublisher.publishAuthFailure(clientIp, path, method, "API Key已过期",
                    Map.of("apiKeyName", apiKeyEntity.getName()));
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "API Key已过期");
        }

        return rateLimitService.tryAcquire("apikey:" + apiKey, apiKeyEntity.getRateLimitCapacity(), 10, java.time.Duration.ofSeconds(1))
                .flatMap(allowed -> {
                    if (!allowed) {
                        log.warn("API Key限流触发: {} - {}", apiKeyEntity.getName(), clientIp);
                        eventPublisher.publishRateLimitTriggered(clientIp, path, method, apiKeyEntity.getName(),
                                Map.of("limitType", "API_KEY", "apiKeyName", apiKeyEntity.getName()));
                        return writeErrorResponse(exchange, HttpStatus.TOO_MANY_REQUESTS, "API Key请求过于频繁");
                    }
                    exchange.getAttributes().put("user", apiKeyEntity.getName());
                    exchange.getAttributes().put("apiKeyId", apiKeyEntity.getKeyId());
                    exchange.getAttributes().put("permissions", apiKeyEntity.getPermissions());

                    eventPublisher.publishAuthSuccess(clientIp, path, method, apiKeyEntity.getName(),
                            Map.of("authType", "API_KEY", "apiKeyName", apiKeyEntity.getName()));

                    return chain.filter(exchange);
                });
    }

    private Mono<Void> authenticateByJwt(ServerWebExchange exchange, WebFilterChain chain, String token,
                                         String clientIp, String method, String path) {
        if (!jwtUtil.validateToken(token)) {
            eventPublisher.publishAuthFailure(clientIp, path, method, "无效或已过期的Token", null);
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "无效或已过期的Token");
        }

        String subject = jwtUtil.getSubject(token);
        String role = jwtUtil.getClaim(token, "role", String.class);

        exchange.getAttributes().put("user", subject);
        exchange.getAttributes().put("role", role);

        eventPublisher.publishAuthSuccess(clientIp, path, method, subject,
                Map.of("authType", "JWT", "role", role));

        return chain.filter(exchange);
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private SysGatewayRoute findRoute(String path) {
        List<SysGatewayRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<SysGatewayRoute>()
                .orderByDesc(SysGatewayRoute::getPath));

        for (SysGatewayRoute route : routes) {
            if (path.startsWith(route.getPath())) {
                return route;
            }
        }
        return null;
    }

    private Mono<Void> writeErrorResponse(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        try {
            ApiResponse<Void> response = ApiResponse.error(status.value(), message);
            byte[] bytes = objectMapper.writeValueAsBytes(response);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(bytes))
            );
        } catch (Exception e) {
            log.error("写入错误响应失败", e);
            return exchange.getResponse().setComplete();
        }
    }
}
