package com.parking.platform.logging.entity;

import com.parking.platform.common.entity.BaseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class LogEntry extends BaseEntity {

    private String level;
    private String service;
    private String message;
    private String traceId;
    private String requestId;
    private String userId;
    private String exception;
    private Map<String, Object> context;
    private Instant timestamp;

    public LogEntry() {
        super();
        this.context = new HashMap<>();
        this.timestamp = Instant.now();
    }

    @Override
    protected String getIdPrefix() { return "log"; }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", getId());
        map.put("timestamp", timestamp);
        map.put("level", level);
        map.put("service", service);
        map.put("message", message);
        map.put("traceId", traceId);
        map.put("requestId", requestId);
        map.put("userId", userId);
        map.put("exception", exception);
        map.put("context", context);
        return map;
    }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getException() { return exception; }
    public void setException(String exception) { this.exception = exception; }
    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
