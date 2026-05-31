package com.tracetopology.web.filter;

import com.tracetopology.common.context.RequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long startTime = System.currentTimeMillis();
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        String requestId = UUID.randomUUID().toString().replace("-", "");

        RequestContext context = RequestContext.builder()
                .traceId(traceId)
                .requestId(requestId)
                .userId(exchange.getRequest().getHeaders().getFirst("X-User-Id"))
                .clientIp(getClientIp(exchange))
                .userAgent(exchange.getRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT))
                .requestTime(Instant.now())
                .build();

        exchange.getAttributes().put("requestContext", context);

        log.info("请求开始: traceId={}, method={}, path={}, ip={}",
                traceId,
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                context.getClientIp());

        return chain.filter(exchange)
                .doOnSuccess(aVoid -> {
                    long duration = System.currentTimeMillis() - startTime;
                    int statusCode = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value()
                            : 200;
                    log.info("请求完成: traceId={}, status={}, duration={}ms",
                            traceId, statusCode, duration);
                    recordAuditLog(exchange, context, statusCode, duration);
                })
                .doOnError(throwable -> {
                    long duration = System.currentTimeMillis() - startTime;
                    log.error("请求异常: traceId={}, error={}, duration={}ms",
                            traceId, throwable.getMessage(), duration, throwable);
                    recordAuditLog(exchange, context, 500, duration);
                });
    }

    private String getClientIp(ServerWebExchange exchange) {
        String xForwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }
        return exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    private void recordAuditLog(ServerWebExchange exchange, RequestContext context,
                                int statusCode, long durationMs) {
        try {
            String operation = exchange.getRequest().getMethod() + " " + exchange.getRequest().getPath();
            log.debug("审计日志: traceId={}, operation={}, status={}, duration={}ms",
                    context.getTraceId(), operation, statusCode, durationMs);
        } catch (Exception e) {
            log.warn("记录审计日志失败: {}", e.getMessage());
        }
    }
}
