package com.datapipeline.gateway.tracing;

import com.datapipeline.common.tracing.TraceContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class TraceManager {

    private final Map<String, TraceContext> activeTraces = new ConcurrentHashMap<>();

    public TraceContext startTrace(String operation, Map<String, String> headers) {
        String traceId = headers.getOrDefault("X-Trace-Id", UUID.randomUUID().toString());
        String parentSpanId = headers.get("X-Parent-Span-Id");

        TraceContext ctx = TraceContext.create(operation, traceId);
        if (parentSpanId != null) {
            ctx.setParentSpanId(parentSpanId);
        }

        activeTraces.put(ctx.getTraceId(), ctx);
        log.debug("Trace started: traceId={}, spanId={}, operation={}",
                ctx.getTraceId(), ctx.getSpanId(), operation);
        return ctx;
    }

    public TraceContext startTrace(String operation) {
        return startTrace(operation, java.util.Collections.emptyMap());
    }

    public void endTrace(TraceContext ctx, boolean success, String errorCode) {
        if (ctx == null) {
            return;
        }
        if (success) {
            ctx.markSuccess();
        } else {
            ctx.markError(errorCode != null ? errorCode : "UNKNOWN");
        }
        activeTraces.remove(ctx.getTraceId());
        log.debug("Trace ended: traceId={}, success={}, durationMs={}",
                ctx.getTraceId(), success, ctx.durationMillis());
    }

    public TraceContext getTrace(String traceId) {
        return activeTraces.get(traceId);
    }

    public int getActiveTraceCount() {
        return activeTraces.size();
    }

    public Map<String, String> createPropagationHeaders(TraceContext ctx) {
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("X-Trace-Id", ctx.getTraceId());
        headers.put("X-Span-Id", ctx.getSpanId());
        if (ctx.getParentSpanId() != null) {
            headers.put("X-Parent-Span-Id", ctx.getParentSpanId());
        }
        return headers;
    }

}
