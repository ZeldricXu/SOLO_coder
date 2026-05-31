package com.device.platform.common;

import lombok.Data;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
public class TraceContext {
    private final String traceId;
    private final String spanId;
    private final Instant startTime;
    private final Map<String, Object> attributes;

    public TraceContext() {
        this.traceId = UUID.randomUUID().toString().replace("-", "");
        this.spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.startTime = Instant.now();
        this.attributes = new HashMap<>();
    }

    public TraceContext(String traceId) {
        this.traceId = traceId != null ? traceId : UUID.randomUUID().toString().replace("-", "");
        this.spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.startTime = Instant.now();
        this.attributes = new HashMap<>();
    }

    public void putAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    public long getDurationMs() {
        return Instant.now().toEpochMilli() - startTime.toEpochMilli();
    }
}
