package com.scheduler.log.pipeline.processor;

import com.scheduler.log.pipeline.model.LogEntry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface LogProcessor {
    String getName();
    Mono<LogEntry> process(LogEntry entry);
    default Flux<LogEntry> process(Flux<LogEntry> entries) {
        return entries.concatMap(this::process);
    }
}
