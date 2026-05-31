package com.scheduler.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Getter
public class BaseEvent extends ApplicationEvent {
    private final String eventId;
    private final String type;
    private final Instant timestamp;
    private final Map<String, Object> payload;

    public BaseEvent(Object source, String type) {
        super(source);
        this.eventId = UUID.randomUUID().toString();
        this.type = type;
        this.timestamp = Instant.now();
        this.payload = new HashMap<>();
    }

    public BaseEvent payload(String key, Object value) {
        this.payload.put(key, value);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T getPayload(String key) {
        return (T) this.payload.get(key);
    }
}
