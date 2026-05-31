package com.scheduler.common.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublisher {
    private final ApplicationEventPublisher publisher;

    public void publish(BaseEvent event) {
        log.debug("Publishing event: {} with id: {}", event.getType(), event.getEventId());
        publisher.publishEvent(event);
    }

    public void publishAsync(BaseEvent event) {
        log.debug("Publishing async event: {} with id: {}", event.getType(), event.getEventId());
        new Thread(() -> publisher.publishEvent(event)).start();
    }
}
