package com.taskflow.logging.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class StructuredLog {

    private String timestamp;
    private String level;
    private String logger;
    private String message;
    private String traceId;
    private String tenantId;
    private String userId;
    private String module;
    private long durationMs;
    private Map<String, Object> metadata;
    private String exception;
    private String stackTrace;

    public static StructuredLogBuilder baseBuilder() {
        return StructuredLog.builder()
                .timestamp(Instant.now().toString())
                .traceId(getCurrentTraceId());
    }

    private static String getCurrentTraceId() {
        try {
            return com.taskflow.logging.context.LogContext.getTraceId();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
