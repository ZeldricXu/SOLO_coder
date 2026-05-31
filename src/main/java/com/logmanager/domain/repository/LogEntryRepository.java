package com.logmanager.domain.repository;

import com.logmanager.domain.model.LogEntry;
import com.logmanager.common.enums.LogLevel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;

public interface LogEntryRepository {
    Mono<LogEntry> save(LogEntry logEntry);
    Flux<LogEntry> saveAll(Iterable<LogEntry> logEntries);
    Flux<LogEntry> findByServiceName(String serviceName);
    Flux<LogEntry> findByServiceNameAndLevel(String serviceName, LogLevel level);
    Flux<LogEntry> findByTraceId(String traceId);
    Flux<LogEntry> findByTimeRange(Instant start, Instant end);
    Mono<Long> countByServiceName(String serviceName);
}
