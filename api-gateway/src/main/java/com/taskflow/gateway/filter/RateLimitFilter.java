package com.taskflow.gateway.filter;

import com.taskflow.common.exception.RateLimitExceededException;
import com.taskflow.gateway.api.RateLimitService;
import com.taskflow.gateway.api.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 限流过滤器
 * 仅依赖RateLimitService和TokenService接口，实现依赖倒置
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter implements WebFilter {

    private final RateLimitService rateLimitService;
    private final TokenService tokenService;

    @Value("${rate.limit.per-second:100}")
    private int defaultRateLimit;

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_LIMIT = "X-RateLimit-Limit";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rateLimitKey = getRateLimitKey(exchange);

        return rateLimitService.tryAcquire(rateLimitKey, defaultRateLimit)
                .flatMap(allowed -> {
                    if (!allowed) {
                        return Mono.error(new RateLimitExceededException("Rate limit exceeded"));
                    }

                    return rateLimitService.getRemaining(rateLimitKey, defaultRateLimit)
                            .doOnNext(remaining -> {
                                exchange.getResponse().getHeaders().set(HEADER_REMAINING, String.valueOf(remaining));
                                exchange.getResponse().getHeaders().set(HEADER_LIMIT, String.valueOf(defaultRateLimit));
                            })
                            .then(chain.filter(exchange));
                });
    }

    private String getRateLimitKey(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());
            if (tokenService.validateToken(token)) {
                String tenantId = tokenService.getTenantId(token);
                String username = tokenService.getUsername(token);
                return "user:" + tenantId + ":" + username;
            }
        }

        String ip = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";

        return "ip:" + ip;
    }
}
