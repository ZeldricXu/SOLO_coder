package com.solocoder.dns.core.service;

import com.solocoder.dns.common.entity.DomainEvent;
import com.solocoder.dns.common.util.IdGenerator;
import com.solocoder.dns.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventPublisher {
    private final ApplicationEventPublisher eventPublisher;

    public void emitEvent(String eventType, Map<String, Object> payload) {
        DomainEvent event = new DomainEvent();
        event.setEventId(IdGenerator.generateEventId());
        event.setEventType(eventType);
        event.setAggregateId(payload.containsKey("entityId") ? (String) payload.get("entityId") : "unknown");
        event.setPayload(payload);
        event.setOccurredAt(LocalDateTime.now());
        event.setSequence(System.currentTimeMillis());

        log.debug("Emitting event: {} - {}", event.getEventId(), eventType);
        eventPublisher.publishEvent(event);
    }
}
