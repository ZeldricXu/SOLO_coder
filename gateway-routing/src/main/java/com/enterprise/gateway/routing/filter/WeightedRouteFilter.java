package com.enterprise.gateway.routing.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Slf4j
@Component
public class WeightedRouteFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (route == null || route.getMetadata() == null) {
            return chain.filter(exchange);
        }

        Map<String, Object> metadata = route.getMetadata();
        Object weightConfig = metadata.get("weight");
        if (weightConfig == null) {
            return chain.filter(exchange);
        }

        @SuppressWarnings("unchecked")
        Map<String, Integer> weights = (Map<String, Integer>) weightConfig;
        if (weights.isEmpty()) {
            return chain.filter(exchange);
        }

        String selectedBackend = selectByWeight(weights);
        if (selectedBackend != null) {
            exchange.getAttributes().put("selectedBackend", selectedBackend);
            log.debug("Weighted routing selected backend: {}", selectedBackend);
        }

        return chain.filter(exchange);
    }

    String selectByWeight(Map<String, Integer> weights) {
        int totalWeight = weights.values().stream().mapToInt(Integer::intValue).sum();
        if (totalWeight <= 0) {
            return null;
        }

        int random = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            cumulative += entry.getValue();
            if (random < cumulative) {
                return entry.getKey();
            }
        }

        return weights.keySet().iterator().next();
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
