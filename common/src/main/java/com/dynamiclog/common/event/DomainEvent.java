package com.dynamiclog.common.event;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class DomainEvent {
    private String eventId;
    private String eventType;
    private String source;
    private LocalDateTime timestamp;
    private Map<String, Object> payload;
    private String traceId;

    public DomainEvent() {
        this.timestamp = LocalDateTime.now();
    }
}
