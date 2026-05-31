package com.taskflow.logging.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AccessLog {
    private String timestamp;
    private String method;
    private String path;
    private String queryString;
    private int status;
    private long durationMs;
    private String clientIp;
    private String userAgent;
    private String traceId;
    private String tenantId;
    private String userId;
    private long requestSize;
    private long responseSize;

    public static AccessLogBuilder builder() {
        return new AccessLogBuilder().timestamp(Instant.now().toString());
    }
}
