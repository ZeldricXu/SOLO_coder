package com.logmanager.service.pipeline;

import com.logmanager.domain.model.LogEntry;
import java.util.Optional;

@FunctionalInterface
public interface LogRouter {
    Optional<String> route(LogEntry logEntry);

    default LogRouter andThen(LogRouter other) {
        return logEntry -> {
            Optional<String> first = this.route(logEntry);
            return first.isPresent() ? first : other.route(logEntry);
        };
    }
}
