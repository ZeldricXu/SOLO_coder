package com.enterprise.gateway.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewayMetricsFilter implements GlobalFilter, Ordered {

    private final MeterRegistry meterRegistry;

    public GatewayMetricsFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route != null ? route.getId() : "unknown";
        String method = exchange.getRequest().getMethod().name();
        String uri = exchange.getRequest().getURI().getPath();

        Timer.Sample sample = Timer.start(meterRegistry);

        return chain.filter(exchange)
                .doOnSuccess(aVoid -> recordMetrics(exchange, routeId, method, uri, sample, null))
                .doOnError(throwable -> recordMetrics(exchange, routeId, method, uri, sample, throwable));
    }

    private void recordMetrics(ServerWebExchange exchange, String routeId, String method, String uri, Timer.Sample sample, Throwable throwable) {
        HttpStatus status = exchange.getResponse().getStatusCode();
        String statusCode = status != null ? String.valueOf(status.value()) : "500";

        sample.stop(Timer.builder("gateway.requests.latency")
                .tag("routeId", routeId)
                .tag("method", method)
                .tag("status", statusCode)
                .tag("uri", uri)
                .register(meterRegistry));

        Counter.builder("gateway.requests.count")
                .tag("routeId", routeId)
                .tag("method", method)
                .tag("status", statusCode)
                .tag("uri", uri)
                .register(meterRegistry)
                .increment();

        if (throwable != null || (status != null && status.isError())) {
            String errorType = throwable != null ? throwable.getClass().getSimpleName() : "HTTP_" + statusCode;
            Counter.builder("gateway.requests.errors")
                    .tag("routeId", routeId)
                    .tag("method", method)
                    .tag("errorType", errorType)
                    .register(meterRegistry)
                    .increment();
        }
    }

    @Override
    public int getOrder() {
        return 50;
    }
}
