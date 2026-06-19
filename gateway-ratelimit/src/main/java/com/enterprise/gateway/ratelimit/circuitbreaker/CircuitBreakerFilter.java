package com.enterprise.gateway.ratelimit.circuitbreaker;

import com.enterprise.gateway.common.model.CircuitBreakerRule;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -40;

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final CircuitBreakerStateListener stateListener;
    private final Map<String, CircuitBreakerRule> ruleCache = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }

        String routeId = route.getId();
        CircuitBreakerRule rule = ruleCache.get(routeId);
        if (rule == null || rule.getStatus() == null || rule.getStatus() != 1) {
            return chain.filter(exchange);
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.getOrCreate(routeId, rule);
        if (circuitBreaker.getState() == CircuitBreaker.State.DISABLED) {
            return chain.filter(exchange);
        }

        stateListener.registerListener(routeId);

        return chain.filter(exchange)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(CallNotPermittedException.class, e -> {
                    log.warn("Circuit breaker is OPEN for route: {}", routeId);
                    exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    return exchange.getResponse().setComplete();
                });
    }

    public void updateRule(CircuitBreakerRule rule) {
        if (rule.getRouteId() != null) {
            ruleCache.put(rule.getRouteId(), rule);
            circuitBreakerRegistry.remove(rule.getRouteId());
        }
    }

    public void removeRule(String routeId) {
        ruleCache.remove(routeId);
        circuitBreakerRegistry.remove(routeId);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
