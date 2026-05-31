package com.scheduler.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
public class RequestFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = request.getHeaders().getFirst("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString();
        }

        ServerHttpRequest modifiedRequest = request.mutate()
                .header("X-Trace-Id", traceId)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Request-Time", Instant.now().toString())
                .build();

        exchange.getAttributes().put("traceId", traceId);
        exchange.getAttributes().put("startTime", System.currentTimeMillis());

        log.debug("Gateway request: {} {}, traceId: {}", request.getMethod(), request.getURI(), traceId);

        return chain.filter(exchange.mutate().request(modifiedRequest).build())
                .then(Mono.fromRunnable(() -> {
                    long duration = System.currentTimeMillis() - (Long) exchange.getAttribute("startTime");
                    log.debug("Gateway response: {} {} took {}ms, traceId: {}",
                            request.getMethod(), request.getURI(), duration, traceId);
                }));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
