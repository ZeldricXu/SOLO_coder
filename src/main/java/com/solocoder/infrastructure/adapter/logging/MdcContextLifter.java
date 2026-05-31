package com.solocoder.infrastructure.adapter.logging;

import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;

@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcContextLifter implements WebFilter {

    private static final String TRACE_ID = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        String finalTraceId = traceId;
        exchange.getResponse().getHeaders().add(TRACE_ID, traceId);

        return chain.filter(exchange)
                .contextWrite(Context.of(TRACE_ID, finalTraceId))
                .doOnSubscribe(subscription -> MDC.put(TRACE_ID, finalTraceId))
                .doFinally(signalType -> MDC.remove(TRACE_ID));
    }
}
