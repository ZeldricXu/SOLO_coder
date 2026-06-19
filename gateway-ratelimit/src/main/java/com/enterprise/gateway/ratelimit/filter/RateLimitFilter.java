package com.enterprise.gateway.ratelimit.filter;

import com.enterprise.gateway.common.enums.RateLimitStrategy;
import com.enterprise.gateway.common.model.RateLimitRule;
import com.enterprise.gateway.ratelimit.strategy.RateLimitStrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -50;
    private static final String X_RATE_LIMIT_LIMIT = "X-RateLimit-Limit";
    private static final String X_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    private static final String X_RATE_LIMIT_RESET = "X-RateLimit-Reset";
    private static final String RETRY_AFTER = "Retry-After";

    private final RateLimitStrategyFactory strategyFactory;
    private final Map<String, RateLimitRule> ruleCache = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }

        String routeId = route.getId();
        RateLimitRule rule = ruleCache.get(routeId);
        if (rule == null || rule.getStatus() == null || rule.getStatus() != 1) {
            return chain.filter(exchange);
        }

        String key = extractRateLimitKey(exchange.getRequest(), routeId);
        RateLimitStrategy strategyType = RateLimitStrategy.valueOf(rule.getStrategy());
        com.enterprise.gateway.ratelimit.strategy.RateLimitStrategy strategy = strategyFactory.getStrategy(strategyType);

        if (strategy == null) {
            return chain.filter(exchange);
        }

        return strategy.tryAcquire(key, rule)
                .flatMap(allowed -> {
                    setRateLimitHeaders(exchange, rule, allowed);
                    if (allowed) {
                        return chain.filter(exchange);
                    } else {
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        exchange.getResponse().getHeaders().set(RETRY_AFTER, "1");
                        log.warn("Rate limit exceeded for key: {}, route: {}", key, routeId);
                        return exchange.getResponse().setComplete();
                    }
                });
    }

    private String extractRateLimitKey(ServerHttpRequest request, String routeId) {
        String userId = request.getHeaders().getFirst("X-User-Id");
        if (userId != null && !userId.isEmpty()) {
            return "user:" + userId;
        }

        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null) {
            String ip = remoteAddress.getAddress().getHostAddress();
            return "ip:" + ip;
        }

        return "route:" + routeId;
    }

    private void setRateLimitHeaders(ServerWebExchange exchange, RateLimitRule rule, boolean allowed) {
        long limit = rule.getCapacity() != null ? rule.getCapacity() :
                (rule.getPermits() != null ? rule.getPermits() : 100);
        long remaining = allowed ? limit - 1 : 0;
        long reset = System.currentTimeMillis() / 1000L + 1;

        exchange.getResponse().getHeaders().set(X_RATE_LIMIT_LIMIT, String.valueOf(limit));
        exchange.getResponse().getHeaders().set(X_RATE_LIMIT_REMAINING, String.valueOf(remaining));
        exchange.getResponse().getHeaders().set(X_RATE_LIMIT_RESET, String.valueOf(reset));
    }

    public void updateRule(RateLimitRule rule) {
        if (rule.getRouteId() != null) {
            ruleCache.put(rule.getRouteId(), rule);
        }
    }

    public void removeRule(String routeId) {
        ruleCache.remove(routeId);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
