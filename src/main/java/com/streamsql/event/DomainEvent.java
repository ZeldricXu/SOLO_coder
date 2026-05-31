package com.streamsql.event;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
public class DomainEvent<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventId;
    private String eventType;
    private String source;
    private LocalDateTime timestamp;
    private T payload;
    private Map<String, Object> metadata;
    private int retryCount;

    public DomainEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = LocalDateTime.now();
        this.metadata = new HashMap<>();
        this.retryCount = 0;
    }

    public DomainEvent(String eventType, T payload) {
        this();
        this.eventType = eventType;
        this.payload = payload;
    }

    public DomainEvent(String eventType, String source, T payload) {
        this(eventType, payload);
        this.source = source;
    }

    public DomainEvent<T> addMetadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }
}
