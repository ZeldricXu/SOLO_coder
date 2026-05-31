package com.logmanager.domain.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class EventPublisher {
    private final ApplicationEventPublisher publisher;

    public void publish(DomainEvent event) {
        publisher.publishEvent(event);
    }
}
