package com.chain.infrastructure.common.context;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
public class TaskContext implements AutoCloseable {

    private String traceId;

    private String entityId;

    private LocalDateTime startTime;

    private Map<String, Object> attributes = new HashMap<>();

    private boolean rolledBack;

    public static TaskContext init(String traceId) {
        TaskContext ctx = new TaskContext();
        ctx.setTraceId(traceId);
        ctx.setStartTime(LocalDateTime.now());
        return ctx;
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    @Override
    public void cleanup() {
        attributes.clear();
    }

    @Override
    public void close() {
        cleanup();
    }
}
