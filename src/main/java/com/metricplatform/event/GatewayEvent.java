package com.metricplatform.event;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayEvent {
    private String eventId;
    private EventType eventType;
    private EventLevel eventLevel;
    private String source;
    private String message;
    private String clientIp;
    private String path;
    private String method;
    private String user;
    private Integer httpStatus;
    private Map<String, Object> metadata;
    private LocalDateTime timestamp;

    public enum EventType {
        AUTH_SUCCESS,
        AUTH_FAILURE,
        RATE_LIMIT_TRIGGERED,
        ROUTE_DISABLED,
        INVALID_TOKEN,
        INVALID_API_KEY,
        API_KEY_EXPIRED,
        PERMISSION_DENIED,
        REQUEST_BLOCKED,
        ROUTE_NOT_FOUND,
        SERVER_ERROR
    }

    public enum EventLevel {
        INFO,
        WARN,
        ERROR,
        CRITICAL
    }
}
