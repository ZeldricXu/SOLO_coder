package com.solocoder.dns.gateway.filter;

import com.solocoder.dns.common.util.IdGenerator;
import com.solocoder.dns.common.util.JsonUtils;
import com.solocoder.dns.gateway.model.RequestLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RequestTracingFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = request.getHeaders().getFirst("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = IdGenerator.generateTraceId();
        }
        String spanId = IdGenerator.generateId("span");
        String parentSpanId = request.getHeaders().getFirst("X-Parent-Span-Id");

        ServerHttpRequest mutatedRequest = request.mutate()
                .header("X-Trace-Id", traceId)
                .header("X-Span-Id", spanId)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

        RequestLog requestLog = new RequestLog();
        requestLog.setTraceId(traceId);
        requestLog.setSpanId(spanId);
        requestLog.setParentSpanId(parentSpanId);
        requestLog.setMethod(request.getMethod().name());
        requestLog.setPath(request.getPath().value());
        requestLog.setClientIp(request.getRemoteAddress() != null ? request.getRemoteAddress().getAddress().getHostAddress() : null);
        requestLog.setUserAgent(request.getHeaders().getFirst(HttpHeaders.USER_AGENT));
        requestLog.setHeaders(extractHeaders(request.getHeaders()));
        requestLog.setStartTime(LocalDateTime.now());
        requestLog.setServiceName("dns-platform");
        requestLog.setOperation(request.getMethod().name() + " " + request.getPath().value());

        exchange.getResponse().getHeaders().add("X-Trace-Id", traceId);

        return chain.filter(mutatedExchange)
                .doOnSuccess(v -> logResponse(exchange, requestLog, null))
                .doOnError(e -> logResponse(exchange, requestLog, e.getMessage()));
    }

    private void logResponse(ServerWebExchange exchange, RequestLog requestLog, String error) {
        ServerHttpResponse response = exchange.getResponse();
        requestLog.setEndTime(LocalDateTime.now());
        requestLog.setDurationMs(java.time.Duration.between(requestLog.getStartTime(), requestLog.getEndTime()).toMillis());
        requestLog.setStatusCode(response.getStatusCode() != null ? response.getStatusCode().value() : 500);
        requestLog.setErrorMessage(error);

        log.info("Request[{}] {} {} - {} ({}ms)",
                requestLog.getTraceId(),
                requestLog.getMethod(),
                requestLog.getPath(),
                requestLog.getStatusCode(),
                requestLog.getDurationMs());

        if (error != null) {
            log.error("Request error: traceId={}, error={}", requestLog.getTraceId(), error);
        }
    }

    private Map<String, String> extractHeaders(HttpHeaders headers) {
        Map<String, String> result = new HashMap<>();
        headers.forEach((key, values) -> {
            if (!key.toLowerCase().contains("authorization") && !key.toLowerCase().contains("cookie")) {
                result.put(key, String.join(",", values));
            }
        });
        return result;
    }
}
