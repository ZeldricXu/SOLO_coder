package com.taskplatform.common.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublisher {

    private final ApplicationEventPublisher publisher;

    public void publish(ApplicationEvent event) {
        try {
            log.debug("Publishing event: {} - {}", event.getEventType(), event.getEventId());
            publisher.publishEvent(event);
        } catch (Exception e) {
            log.error("Failed to publish event: {}", event.getEventType(), e);
        }
    }

    public void publishAsync(ApplicationEvent event) {
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    publish(event);
                } catch (Exception e) {
                    log.error("Async event publishing failed", e);
                }
            });
        } catch (Exception e) {
            log.error("Failed to schedule async event", e);
        }
    }
}
