package com.datapipeline.gateway.filter;

import com.datapipeline.gateway.logging.RequestLogger;
import com.datapipeline.gateway.tracing.TraceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class RequestFilter {

    private final RequestLogger requestLogger;
    private final TraceManager traceManager;
    private final List<String> rateLimitPaths;
    private final int maxRequestsPerSecond;

    public RequestFilter(RequestLogger requestLogger, TraceManager traceManager) {
        this(requestLogger, traceManager, List.of("/api/"), 1000);
    }

    public RequestFilter(RequestLogger requestLogger, TraceManager traceManager,
                         List<String> rateLimitPaths, int maxRequestsPerSecond) {
        this.requestLogger = requestLogger;
        this.traceManager = traceManager;
        this.rateLimitPaths = rateLimitPaths;
        this.maxRequestsPerSecond = maxRequestsPerSecond;
    }

    public Mono<Void> filter(ServerWebExchange exchange, FilterChain chain) {
        long startTime = System.currentTimeMillis();
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        String method = request.getMethod().name();
        String path = request.getPath().value();
        Map<String, String> headers = request.getHeaders().toSingleValueMap();

        var traceCtx = traceManager.startTrace("gateway_request", headers);

        requestLogger.logRequest(method, path, headers, traceCtx.getTraceId());

        response.beforeCommit(() -> {
            long durationMs = System.currentTimeMillis() - startTime;
            HttpStatus status = response.getStatusCode();
            requestLogger.logResponse(method, path, status != null ? status : HttpStatus.OK,
                    durationMs, traceCtx.getTraceId());
            traceManager.endTrace(traceCtx, status == null || status.is2xxSuccessful(),
                    status != null ? String.valueOf(status.value()) : null);
            return Mono.empty();
        });

        return chain.filter(exchange);
    }

    @FunctionalInterface
    public interface FilterChain {
        Mono<Void> filter(ServerWebExchange exchange);
    }

}
