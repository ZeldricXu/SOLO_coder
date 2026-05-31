package com.iotplatform.gateway.filter;

import com.iotplatform.common.constant.MetricConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RequestLoggingFilter implements WebFilter {

    private final MeterRegistry meterRegistry;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long startTime = System.nanoTime();
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getURI().getPath();
        String clientIp = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";

        if (log.isDebugEnabled()) {
            log.debug("Request started: {} {} from {}", method, path, clientIp);
        }

        return chain.filter(exchange)
                .doOnSuccess(v -> logSuccess(exchange, method, path, clientIp, startTime))
                .doOnError(e -> logError(exchange, method, path, clientIp, startTime, e));
    }

    private void logSuccess(ServerWebExchange exchange, String method, String path, String clientIp, long startTime) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        int statusCode = exchange.getResponse().getStatusCode() != null
                ? exchange.getResponse().getStatusCode().value()
                : 200;

        meterRegistry.timer(MetricConstants.GATEWAY_REQUEST_DURATION,
                        "method", method,
                        "status", String.valueOf(statusCode))
                .record(durationMs, TimeUnit.MILLISECONDS);

        if (log.isInfoEnabled()) {
            log.info("Request completed: {} {} {} - {}ms", method, path, statusCode, durationMs);
        }
    }

    private void logError(ServerWebExchange exchange, String method, String path, String clientIp, long startTime, Throwable e) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        int statusCode = exchange.getResponse().getStatusCode() != null
                ? exchange.getResponse().getStatusCode().value()
                : 500;

        log.error("Request failed: {} {} {} - {}ms, error: {}", method, path, statusCode, durationMs, e.getMessage(), e);

        meterRegistry.timer(MetricConstants.GATEWAY_REQUEST_DURATION,
                        "method", method,
                        "status", String.valueOf(statusCode),
                        "error", e.getClass().getSimpleName())
                .record(durationMs, TimeUnit.MILLISECONDS);
    }
}
