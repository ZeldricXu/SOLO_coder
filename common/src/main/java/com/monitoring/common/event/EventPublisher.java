package com.monitoring.common.event;

import reactor.core.publisher.Mono;

public interface EventPublisher {

    public Mono<Void> publish(MonitoringEvent event);
}
