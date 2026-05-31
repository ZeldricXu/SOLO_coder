package com.chaoslab.common;

import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

public class TraceContext {

    public static final String TRACE_ID_KEY = "traceId";

    public static Mono<String> getTraceId() {
        return Mono.deferContextual(ctx -> {
            if (ctx.hasKey(TRACE_ID_KEY)) {
                return Mono.just(ctx.get(TRACE_ID_KEY));
            }
            return Mono.just(generateTraceId());
        });
    }

    public static String getTraceIdFromContext(ContextView ctx) {
        return ctx.getOrDefault(TRACE_ID_KEY, generateTraceId());
    }

    public static String generateTraceId() {
        return "trace-" + System.currentTimeMillis() + "-" +
                Integer.toHexString((int) (Math.random() * 0x10000));
    }
}
