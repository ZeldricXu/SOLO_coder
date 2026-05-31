package com.monitoring.common.context;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingContext {

    private String traceId;

    private String requestId;

    private Instant startTime;

    @Builder.Default
    private Map<String, Object> attributes = new ConcurrentHashMap<>();

    @Builder.Default
    private Map<String, Long> timing = new HashMap<>();

    private String phase;

    private Boolean success;

    private String errorMessage;

    public void recordTiming(String key) {
        timing.put(key, System.currentTimeMillis());
    }

    public long getElapsedMillis() {
        return System.currentTimeMillis() - startTime.toEpochMilli();
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public void cleanup() {
        attributes.clear();
        timing.clear();
    }
}
