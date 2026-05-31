package com.chaoslab.event;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
public class DomainEvent<T> {

    private String eventId;
    private String eventType;
    private Integer eventVersion = 1;
    private String aggregateId;
    private String aggregateType;
    private T payload;
    private Map<String, Object> metadata;
    private LocalDateTime timestamp;

    public DomainEvent() {
        this.eventId = "evt-" + UUID.randomUUID().toString().substring(0, 8);
        this.timestamp = LocalDateTime.now();
        this.metadata = new HashMap<>();
    }

    public DomainEvent(String eventType, String aggregateId, String aggregateType, T payload) {
        this();
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.payload = payload;
    }

    public void addMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }
}

