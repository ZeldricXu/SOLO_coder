package com.enterprise.gateway.observability.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Component
public class DistributedTracingFilter implements GlobalFilter, Ordered {

    private final Tracer tracer;
    private final TraceContextPropagator traceContextPropagator;

    public DistributedTracingFilter(Tracer tracer, TraceContextPropagator traceContextPropagator) {
        this.tracer = tracer;
        this.traceContextPropagator = traceContextPropagator;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        traceContextPropagator.extractTraceContext(tracer, exchange.getRequest().getHeaders());

        Span parentSpan = tracer.currentSpan();
        Span span = tracer.nextSpan(parentSpan).name("gateway.request").start();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String routeId = route != null ? route.getId() : "unknown";

            span.tag("http.method", exchange.getRequest().getMethod().name());
            span.tag("http.path", exchange.getRequest().getURI().getPath());
            span.tag("http.route_id", routeId);
            span.tag("user.id", exchange.getRequest().getHeaders().getFirst("X-User-Id") != null ?
                    exchange.getRequest().getHeaders().getFirst("X-User-Id") : "anonymous");
            span.tag("client.ip", getClientIp(exchange));

            HttpHeaders mutatedHeaders = HttpHeaders.writableHttpHeaders(exchange.getRequest().getHeaders());
            traceContextPropagator.injectTraceContext(tracer, mutatedHeaders);

            exchange.getResponse().getHeaders().set("X-Trace-Id", span.context().traceId());
            exchange.getResponse().getHeaders().set("X-Span-Id", span.context().spanId());

            return chain.filter(exchange)
                    .doOnSuccess(aVoid -> span.end())
                    .doOnError(throwable -> {
                        span.error(throwable);
                        span.end();
                    });
        }
    }

    private String getClientIp(ServerWebExchange exchange) {
        String xForwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
