package com.logmanager.common.utils;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import java.util.UUID;

public class TraceIdContext {
    public static final String TRACE_ID_KEY = "traceId";

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

    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
