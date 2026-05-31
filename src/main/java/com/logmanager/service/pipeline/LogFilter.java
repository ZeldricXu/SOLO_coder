package com.logmanager.service.pipeline;

import com.logmanager.domain.model.LogEntry;

@FunctionalInterface
public interface LogFilter {
    boolean accept(LogEntry logEntry);

    default LogFilter and(LogFilter other) {
        return logEntry -> this.accept(logEntry) && other.accept(logEntry);
    }

    default LogFilter or(LogFilter other) {
        return logEntry -> this.accept(logEntry) || other.accept(logEntry);
    }

    default LogFilter negate() {
        return logEntry -> !this.accept(logEntry);
    }
}
