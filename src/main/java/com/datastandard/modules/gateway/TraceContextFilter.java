package com.datastandard.modules.gateway;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class TraceContextFilter implements WebFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String SPAN_ID_HEADER = "X-Span-Id";
    public static final String PARENT_SPAN_ID_HEADER = "X-Parent-Span-Id";

    public static final String REQUEST_ID_ATTR = "requestId";
    public static final String TRACE_ID_ATTR = "traceId";
    public static final String SPAN_ID_ATTR = "spanId";

    private final RequestIdGenerator requestIdGenerator;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        String requestId = getOrGenerate(request.getHeaders().getFirst(REQUEST_ID_HEADER),
                requestIdGenerator::generateRequestId);
        String traceId = getOrGenerate(request.getHeaders().getFirst(TRACE_ID_HEADER),
                requestIdGenerator::generateTraceId);
        String parentSpanId = request.getHeaders().getFirst(PARENT_SPAN_ID_HEADER);
        String spanId = requestIdGenerator.generateNextSpanId(parentSpanId);

        exchange.getAttributes().put(REQUEST_ID_ATTR, requestId);
        exchange.getAttributes().put(TRACE_ID_ATTR, traceId);
        exchange.getAttributes().put(SPAN_ID_ATTR, spanId);

        ServerHttpRequest mutatedRequest = request.mutate()
                .header(REQUEST_ID_HEADER, requestId)
                .header(TRACE_ID_HEADER, traceId)
                .header(SPAN_ID_HEADER, spanId)
                .build();

        exchange.getResponse().getHeaders().add(REQUEST_ID_HEADER, requestId);
        exchange.getResponse().getHeaders().add(TRACE_ID_HEADER, traceId);
        exchange.getResponse().getHeaders().add(SPAN_ID_HEADER, spanId);

        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .contextWrite(Context.of(
                        REQUEST_ID_ATTR, requestId,
                        TRACE_ID_ATTR, traceId,
                        SPAN_ID_ATTR, spanId
                ));
    }

    private String getOrGenerate(String value, java.util.function.Supplier<String> generator) {
        if (StrUtil.isNotBlank(value)) {
            return value.trim();
        }
        return generator.get();
    }
}
