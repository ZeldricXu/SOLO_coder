package com.datapipeline.core;

import com.datapipeline.common.model.ConfigDefinition;
import com.datapipeline.common.model.Entity;
import com.datapipeline.common.tracing.TraceContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestContext {

    private String requestId;
    private String traceId;
    private String namespace;
    private Map<String, Object> params;
    private Map<String, Object> payload;
    private ConfigDefinition config;
    private Entity resource;
    private TraceContext traceContext;
    @Builder.Default
    private Instant startTime = Instant.now();
    private Duration timeout;
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();
    @Builder.Default
    private AtomicBoolean cancelled = new AtomicBoolean(false);

    public boolean isTimedOut() {
        if (timeout == null) {
            return false;
        }
        return Duration.between(startTime, Instant.now()).compareTo(timeout) > 0;
    }

    public void cancel() {
        this.cancelled.set(true);
    }

    public boolean isCancelled() {
        return this.cancelled.get();
    }

    public <T> Optional<T> getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        return Optional.empty();
    }

    public RequestContext setAttribute(String key, Object value) {
        this.attributes.put(key, value);
        return this;
    }

}
