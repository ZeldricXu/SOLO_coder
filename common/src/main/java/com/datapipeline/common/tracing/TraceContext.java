package com.datapipeline.common.tracing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceContext {

    @Builder.Default
    private String traceId = UUID.randomUUID().toString();
    @Builder.Default
    private String spanId = UUID.randomUUID().toString().substring(0, 16);
    private String parentSpanId;
    private String operation;
    @Builder.Default
    private Instant startTime = Instant.now();
    private Instant endTime;
    private boolean success;
    private String errorCode;
    @Builder.Default
    private Map<String, String> tags = new HashMap<>();
    @Builder.Default
    private Map<String, Object> metrics = new HashMap<>();

    private static final ThreadLocal<TraceContext> HOLDER = new ThreadLocal<>();

    public static TraceContext create(String operation) {
        TraceContext ctx = TraceContext.builder()
                .operation(operation)
                .build();
        HOLDER.set(ctx);
        return ctx;
    }

    public static TraceContext create(String operation, String traceId) {
        TraceContext ctx = TraceContext.builder()
                .traceId(traceId)
                .operation(operation)
                .build();
        HOLDER.set(ctx);
        return ctx;
    }

    public static TraceContext current() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public TraceContext markSuccess() {
        this.success = true;
        this.endTime = Instant.now();
        return this;
    }

    public TraceContext markError(String errorCode) {
        this.success = false;
        this.errorCode = errorCode;
        this.endTime = Instant.now();
        return this;
    }

    public long durationMillis() {
        if (endTime == null) {
            return Instant.now().toEpochMilli() - startTime.toEpochMilli();
        }
        return endTime.toEpochMilli() - startTime.toEpochMilli();
    }

    public TraceContext tag(String key, String value) {
        this.tags.put(key, value);
        return this;
    }

    public TraceContext metric(String key, Object value) {
        this.metrics.put(key, value);
        return this;
    }

}
