package com.tracetopology.core.context;

import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Getter
public class ProcessingContext implements AutoCloseable {

    private final String traceId;
    private final Instant startTime;
    private final Map<String, Object> attributes;
    private String phase;
    private boolean rolledBack;
    private boolean completed;

    public ProcessingContext(String traceId) {
        this.traceId = traceId != null ? traceId : UUID.randomUUID().toString();
        this.startTime = Instant.now();
        this.attributes = new HashMap<>();
        this.phase = "initialized";
    }

    public static ProcessingContext init(String traceId) {
        return new ProcessingContext(traceId);
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public void setAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return this.attributes.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, Class<T> type) {
        Object value = this.attributes.get(key);
        return type.isInstance(value) ? (T) value : null;
    }

    public Duration getElapsedTime() {
        return Duration.between(startTime, Instant.now());
    }

    public void markRolledBack() {
        this.rolledBack = true;
    }

    public void markCompleted() {
        this.completed = true;
    }

    @Override
    public void close() {
        cleanup();
    }

    public void cleanup() {
        this.attributes.clear();
    }
}
