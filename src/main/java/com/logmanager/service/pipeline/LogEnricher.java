package com.logmanager.service.pipeline;

import com.logmanager.domain.model.LogEntry;

@FunctionalInterface
public interface LogEnricher {
    LogEntry enrich(LogEntry logEntry);

    default LogEnricher andThen(LogEnricher other) {
        return logEntry -> other.enrich(this.enrich(logEntry));
    }
}
