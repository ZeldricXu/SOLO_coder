package com.solocoder.dns.gateway.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class RequestLog implements Serializable {
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String serviceName;
    private String operation;
    private String method;
    private String path;
    private String clientIp;
    private String userAgent;
    private Integer statusCode;
    private Long durationMs;
    private Map<String, String> headers;
    private String requestBody;
    private String responseBody;
    private String errorMessage;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
