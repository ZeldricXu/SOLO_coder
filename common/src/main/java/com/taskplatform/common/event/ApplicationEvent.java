package com.taskplatform.common.event;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
public class ApplicationEvent {

    private String eventId;
    private String eventType;
    private String source;
    private LocalDateTime timestamp;
    private Map<String, Object> payload;
    private Map<String, String> metadata;

    public ApplicationEvent() {
        this.timestamp = LocalDateTime.now();
        this.metadata = new HashMap<>();
    }

    public ApplicationEvent(String eventType, Map<String, Object> payload) {
        this();
        this.eventType = eventType;
        this.payload = payload;
    }

    public static ApplicationEvent of(String type, Map<String, Object> payload) {
        return new ApplicationEvent(type, payload);
    }

    public ApplicationEvent withMetadata(String key, String value) {
        this.metadata.put(key, value);
        return this;
    }
}
