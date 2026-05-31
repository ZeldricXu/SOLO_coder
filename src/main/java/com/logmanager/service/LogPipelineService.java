package com.logmanager.service;

import com.logmanager.domain.model.LogEntry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;

public interface LogPipelineService {
    Mono<LogEntry> collect(LogEntry logEntry);
    Flux<LogEntry> collectBatch(Flux<LogEntry> logEntries);
    Flux<LogEntry> filterByLevel(String serviceName, String level);
    Flux<LogEntry> searchByTraceId(String traceId);
    Mono<Map<String, Long>> getStats(String serviceName);

    Mono<Map<String, Object>> getCacheStats();
    Mono<Void> invalidateCache(String traceId);
    Mono<Void> invalidateAllCache();
    Mono<Void> warmupCache();
}
