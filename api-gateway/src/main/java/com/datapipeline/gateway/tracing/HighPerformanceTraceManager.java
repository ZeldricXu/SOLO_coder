package com.datapipeline.gateway.tracing;

import com.datapipeline.common.tracing.TraceContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class HighPerformanceTraceManager {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String SPAN_ID_HEADER = "X-Span-Id";
    public static final String PARENT_SPAN_ID_HEADER = "X-Parent-Span-Id";

    private final ConcurrentHashMap<String, TraceContext> activeTraces;
    private final AtomicInteger traceCount = new AtomicInteger(0);

    public HighPerformanceTraceManager() {
        this(1000);
    }

    public HighPerformanceTraceManager(int expectedSize) {
        this.activeTraces = new ConcurrentHashMap<>(expectedSize, 0.75f, 1);
    }

    public TraceContext startTrace(String operation, Map<String, String> headers) {
        String traceId = extractTraceId(headers);
        String parentSpanId = extractParentSpanId(headers);

        TraceContext ctx = TraceContext.create(operation, traceId);
        if (parentSpanId != null && !parentSpanId.isEmpty()) {
            ctx.setParentSpanId(parentSpanId);
        }

        activeTraces.put(ctx.getTraceId(), ctx);
        traceCount.incrementAndGet();

        log.debug("Trace started: traceId={}, spanId={}, operation={}",
                ctx.getTraceId(), ctx.getSpanId(), operation);
        return ctx;
    }

    public TraceContext startTrace(String operation) {
        return startTrace(operation, Collections.emptyMap());
    }

    public void endTrace(TraceContext ctx, boolean success, String errorCode) {
        if (ctx == null) {
            return;
        }

        String traceId = ctx.getTraceId();

        if (success) {
            ctx.markSuccess();
        } else {
            ctx.markError(errorCode != null ? errorCode : "UNKNOWN");
        }

        TraceContext removed = activeTraces.remove(traceId);
        if (removed != null) {
            log.debug("Trace ended: traceId={}, success={}, durationMs={}",
                    traceId, success, ctx.durationMillis());
        }
    }

    public TraceContext getTrace(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            return null;
        }
        return activeTraces.get(traceId);
    }

    public int getActiveTraceCount() {
        return activeTraces.size();
    }

    public long getTotalTraceCount() {
        return traceCount.get();
    }

    public Map<String, String> createPropagationHeaders(TraceContext ctx) {
        if (ctx == null) {
            return Collections.emptyMap();
        }

        Map<String, String> headers = new HashMap<>(3);
        headers.put(TRACE_ID_HEADER, ctx.getTraceId());
        headers.put(SPAN_ID_HEADER, ctx.getSpanId());

        String parentSpanId = ctx.getParentSpanId();
        if (parentSpanId != null && !parentSpanId.isEmpty()) {
            headers.put(PARENT_SPAN_ID_HEADER, parentSpanId);
        }

        return headers;
    }

    private String extractTraceId(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return generateTraceId();
        }
        String traceId = headers.get(TRACE_ID_HEADER);
        if (traceId == null) {
            traceId = headers.get("x-trace-id");
        }
        return (traceId != null && !traceId.isEmpty()) ? traceId : generateTraceId();
    }

    private String extractParentSpanId(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        String parentSpanId = headers.get(PARENT_SPAN_ID_HEADER);
        if (parentSpanId == null) {
            parentSpanId = headers.get("x-parent-span-id");
        }
        return (parentSpanId != null && !parentSpanId.isEmpty()) ? parentSpanId : null;
    }

    private static String generateTraceId() {
        return UUID.randomUUID().toString();
    }

}
