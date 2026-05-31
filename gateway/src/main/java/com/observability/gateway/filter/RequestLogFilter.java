package com.observability.gateway.filter;

import com.observability.common.context.RequestContext;
import com.observability.common.context.RequestContextHolder;
import com.observability.common.util.IdGenerator;
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

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter implements WebFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String NAMESPACE_HEADER = "X-Namespace";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = request.getHeaders().getFirst(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = IdGenerator.generateTraceId();
        }

        String userId = request.getHeaders().getFirst(USER_ID_HEADER);
        String namespace = request.getHeaders().getFirst(NAMESPACE_HEADER);

        RequestContext context = RequestContext.create(traceId)
                .userId(userId)
                .namespace(namespace);

        exchange.getResponse().getHeaders().add(TRACE_ID_HEADER, traceId);

        long startTime = System.currentTimeMillis();
        log.info("Request started - traceId: {}, method: {}, path: {}, client: {}",
                traceId, request.getMethod(), request.getPath(), request.getRemoteAddress());

        return chain.filter(exchange)
                .contextWrite(RequestContextHolder.set(context))
                .doOnSuccess(aVoid -> logRequestCompletion(exchange, context, startTime, null))
                .doOnError(throwable -> logRequestCompletion(exchange, context, startTime, throwable));
    }

    private void logRequestCompletion(ServerWebExchange exchange, RequestContext context, long startTime, Throwable throwable) {
        long duration = System.currentTimeMillis() - startTime;
        int statusCode = exchange.getResponse().getStatusCode() != null ?
                exchange.getResponse().getStatusCode().value() : 500;

        if (throwable != null) {
            log.error("Request failed - traceId: {}, method: {}, path: {}, status: {}, duration: {}ms, error: {}",
                    context.getTraceId(), exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), statusCode, duration, throwable.getMessage());
        } else {
            log.info("Request completed - traceId: {}, method: {}, path: {}, status: {}, duration: {}ms",
                    context.getTraceId(), exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), statusCode, duration);
        }
    }
}
