package com.logmanager.domain.event;

import lombok.Data;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
public class DomainEvent {
    private String eventId;
    private String eventType;
    private String aggregateId;
    private String aggregateType;
    private Instant occurredAt;
    private Map<String, Object> payload = new HashMap<>();
    private Map<String, String> metadata = new HashMap<>();

    public DomainEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = Instant.now();
    }

    public DomainEvent(String eventType, String aggregateId, String aggregateType) {
        this();
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
    }
}
