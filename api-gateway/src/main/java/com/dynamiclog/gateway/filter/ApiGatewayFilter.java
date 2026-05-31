package com.dynamiclog.gateway.filter;

import com.dynamiclog.gateway.service.JwtAuthService;
import com.dynamiclog.gateway.service.RateLimitingService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiGatewayFilter implements WebFilter {

    private final JwtAuthService authService;
    private final RateLimitingService rateLimitingService;

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/actuator/health"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (PUBLIC_PATHS.contains(path)) {
            return chain.filter(exchange);
        }

        String clientId = exchange.getRequest().getHeaders().getFirst("X-Client-Id");
        if (clientId == null) {
            clientId = exchange.getRequest().getRemoteAddress()
                    .map(addr -> addr.getAddress().getHostAddress())
                    .orElse("unknown");
        }

        return rateLimitingService.tryConsume(clientId, 1)
                .flatMap(allowed -> {
                    if (!allowed) {
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        return exchange.getResponse().setComplete();
                    }
                    return authenticate(exchange, chain);
                });
    }

    private Mono<Void> authenticate(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(7);
        return authService.validateToken(token)
                .flatMap(claims -> {
                    exchange.getAttributes().put("userClaims", claims);
                    exchange.getAttributes().put("userId", claims.getSubject());
                    return authorize(exchange, chain, claims);
                })
                .onErrorResume(e -> {
                    log.warn("Authentication failed: {}", e.getMessage());
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                });
    }

    private Mono<Void> authorize(ServerWebExchange exchange, WebFilterChain chain, Claims claims) {
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();

        Set<String> adminRoles = Set.of("ADMIN", "SUPER_ADMIN");
        Set<String> writeRoles = Set.of("ADMIN", "SUPER_ADMIN", "EDITOR");

        if (path.startsWith("/api/v1/admin") && method.equals("DELETE")) {
            return authService.hasAnyRole(claims, adminRoles)
                    .flatMap(hasRole -> {
                        if (!hasRole) {
                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            return exchange.getResponse().setComplete();
                        }
                        return chain.filter(exchange);
                    });
        }

        if (Set.of("POST", "PUT", "DELETE").contains(method) && !path.startsWith("/api/v1/public")) {
            return authService.hasAnyRole(claims, writeRoles)
                    .flatMap(hasRole -> {
                        if (!hasRole) {
                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            return exchange.getResponse().setComplete();
                        }
                        return chain.filter(exchange);
                    });
        }

        return chain.filter(exchange);
    }
}
