package com.logmanager.service.pipeline;

import com.logmanager.domain.model.LogEntry;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface LogDestination {
    Mono<Void> deliver(LogEntry logEntry);

    default LogDestination and(LogDestination other) {
        return logEntry -> this.deliver(logEntry).then(other.deliver(logEntry));
    }
}
