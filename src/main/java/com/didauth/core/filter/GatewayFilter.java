package com.didauth.core.filter;

import com.didauth.core.audit.AuditLogService;
import com.didauth.core.context.RequestContext;
import com.didauth.core.context.RequestContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class GatewayFilter implements WebFilter {

    private final AuditLogService auditLogService;
    private final MeterRegistry meterRegistry;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = request.getHeaders().getFirst("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        String path = request.getPath().value();
        String method = request.getMethod().name();
        String module = extractModule(path);
        String operation = method + " " + path;
        String ipAddress = request.getRemoteAddress() != null ? request.getRemoteAddress().getAddress().getHostAddress() : null;
        String userAgent = request.getHeaders().getFirst(HttpHeaders.USER_AGENT);

        RequestContext context = RequestContext.create(traceId);
        context.setModule(module);
        context.setOperation(operation);
        context.setIpAddress(ipAddress);
        context.setUserAgent(userAgent);

        Timer.Sample sample = Timer.start(meterRegistry);

        exchange.getResponse().getHeaders().set("X-Trace-Id", traceId);

        return chain.filter(exchange)
                .contextWrite(ctx -> RequestContextHolder.set(ctx, context))
                .doOnSuccess(aVoid -> {
                    long durationMs = sample.stop(Timer.builder("http.request.duration")
                            .tag("method", method)
                            .tag("path", path)
                            .tag("status", String.valueOf(exchange.getResponse().getStatusCode() != null ?
                                    exchange.getResponse().getStatusCode().value() : 200))
                            .register(meterRegistry)) / 1_000_000;

                    context.setStatus("SUCCESS");
                    auditLogService.recordAuditLog(
                            traceId,
                            null,
                            module,
                            operation,
                            null,
                            null,
                            "SUCCESS",
                            null,
                            ipAddress,
                            userAgent,
                            durationMs
                    ).subscribe();
                })
                .doOnError(throwable -> {
                    long durationMs = sample.stop(Timer.builder("http.request.duration")
                            .tag("method", method)
                            .tag("path", path)
                            .tag("status", "500")
                            .register(meterRegistry)) / 1_000_000;

                    context.setStatus("ERROR");
                    context.setErrorMessage(throwable.getMessage());
                    auditLogService.recordAuditLog(
                            traceId,
                            null,
                            module,
                            operation,
                            null,
                            null,
                            "ERROR",
                            throwable.getMessage(),
                            ipAddress,
                            userAgent,
                            durationMs
                    ).subscribe();
                });
    }

    private String extractModule(String path) {
        String[] parts = path.split("/");
        if (parts.length >= 4) {
            return parts[3];
        }
        return "system";
    }
}
