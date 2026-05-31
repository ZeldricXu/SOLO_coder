package com.taskflow.gateway.filter;

import com.taskflow.common.exception.UnauthorizedException;
import com.taskflow.gateway.api.AuthenticationService;
import com.taskflow.gateway.api.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 认证过滤器
 * 仅依赖TokenService和AuthenticationService接口，实现依赖倒置
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthenticationFilter implements WebFilter {

    private final TokenService tokenService;
    private final AuthenticationService authenticationService;

    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return Mono.error(new UnauthorizedException("Missing or invalid authorization header"));
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        if (!tokenService.validateToken(token)) {
            return Mono.error(new UnauthorizedException("Invalid or expired token"));
        }

        return authenticationService.getAuthentication(token)
                .switchIfEmpty(Mono.error(new UnauthorizedException("Could not authenticate user")))
                .flatMap(authentication -> {
                    String tenantId = tokenService.getTenantId(token);
                    exchange.getRequest().mutate().header("X-Tenant-Id", tenantId);

                    return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
                });
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/api/v1/auth/") ||
                path.startsWith("/actuator/") ||
                path.equals("/health");
    }
}
