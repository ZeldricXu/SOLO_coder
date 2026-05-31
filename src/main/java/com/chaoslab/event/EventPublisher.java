package com.chaoslab.event;

import com.chaoslab.common.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public <T> Mono<DomainEvent<T>> publish(DomainEvent<T> event) {
        return TraceContext.getTraceId()
                .doOnNext(traceId -> event.addMetadata("traceId", traceId))
                .doOnNext(traceId -> {
                    log.debug("Publishing event: {} type: {} aggregate: {}",
                            event.getEventId(), event.getEventType(), event.getAggregateId());
                    applicationEventPublisher.publishEvent(event);
                })
                .thenReturn(event);
    }

    public <T> Mono<DomainEvent<T>> publish(String eventType, String aggregateId,
                                             String aggregateType, T payload) {
        DomainEvent<T> event = new DomainEvent<>(eventType, aggregateId, aggregateType, payload);
        return publish(event);
    }
}
