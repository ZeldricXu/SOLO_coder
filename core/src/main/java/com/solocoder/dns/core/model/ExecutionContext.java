package com.solocoder.dns.core.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
public class ExecutionContext implements Serializable {
    private String traceId;
    private String runId;
    private String namespace;
    private String userId;
    private LocalDateTime startTime;
    private Map<String, Object> attributes;
    private Map<String, Object> transactionState;

    public ExecutionContext() {
        this.startTime = LocalDateTime.now();
        this.attributes = new ConcurrentHashMap<>();
        this.transactionState = new ConcurrentHashMap<>();
    }

    public long getElapsedMs() {
        return java.time.Duration.between(startTime, LocalDateTime.now()).toMillis();
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public void cleanup() {
        attributes.clear();
        transactionState.clear();
    }
}
