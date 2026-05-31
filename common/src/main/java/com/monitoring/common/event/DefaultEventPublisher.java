package com.monitoring.common.event;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
public class DefaultEventPublisher implements EventPublisher {

    @Override
    public Mono<Void> publish(MonitoringEvent event) {
        return Mono.fromRunnable(() -> {
            log.info("Publishing event: type={}, source={}", event.getEventType(), event.getSource());
        });
    }
}
