package com.didauth.core.context;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
public class RequestContext implements Serializable {

    private String traceId;
    private String userId;
    private String module;
    private String operation;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime startTime;
    private Map<String, Object> attributes = new HashMap<>();
    private String status;
    private String errorMessage;

    public static RequestContext create(String traceId) {
        RequestContext ctx = new RequestContext();
        ctx.setTraceId(traceId);
        ctx.setStartTime(LocalDateTime.now());
        return ctx;
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }
}
