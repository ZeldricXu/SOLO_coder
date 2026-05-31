package com.nftindexer.common;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;

public class TraceContext {

    public static final String TRACE_ID_KEY = "traceId";

    private TraceContext() {
    }

    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static Mono<String> getTraceId() {
        return Mono.deferContextual(ctx -> {
            if (ctx.hasKey(TRACE_ID_KEY)) {
                return Mono.just(ctx.get(TRACE_ID_KEY));
            }
            return Mono.just(generateTraceId());
        });
    }

    public static Context withTraceId(String traceId) {
        return Context.of(TRACE_ID_KEY, traceId);
    }

    public static Context withNewTraceId() {
        return Context.of(TRACE_ID_KEY, generateTraceId());
    }
}
