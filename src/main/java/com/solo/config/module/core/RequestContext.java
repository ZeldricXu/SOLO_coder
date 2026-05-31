package com.solo.config.module.core;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
public class RequestContext {

    private String traceId;
    private String requestId;
    private LocalDateTime startTime;
    private Map<String, Object> attributes;
    private String userId;
    private String ipAddress;
    private String userAgent;

    public RequestContext() {
        this.traceId = UUID.randomUUID().toString().replace("-", "");
        this.requestId = UUID.randomUUID().toString().replace("-", "");
        this.startTime = LocalDateTime.now();
        this.attributes = new HashMap<>();
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public long getElapsedMs() {
        return java.time.Duration.between(startTime, LocalDateTime.now()).toMillis();
    }
}
