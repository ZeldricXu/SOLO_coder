package com.nftindexer.config;

import com.nftindexer.event.DomainEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Configuration
public class EventBusConfig {

    @Bean
    public Sinks.Many<DomainEvent> eventSink() {
        return Sinks.many().multicast().onBackpressureBuffer();
    }

    @Bean
    public Flux<DomainEvent> eventFlux(Sinks.Many<DomainEvent> eventSink) {
        return eventSink.asFlux()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(event -> log.debug("Event emitted: {} - {}", event.getEventType(), event.getEventId()))
                .onErrorContinue((e, obj) -> log.error("Error processing event: {}", obj, e));
    }
}
