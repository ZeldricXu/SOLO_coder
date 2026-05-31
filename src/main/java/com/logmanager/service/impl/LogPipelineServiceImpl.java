package com.logmanager.service.impl;

import com.logmanager.common.enums.LogLevel;
import com.logmanager.domain.event.DomainEvent;
import com.logmanager.domain.event.EventPublisher;
import com.logmanager.domain.model.LogEntry;
import com.logmanager.domain.repository.LogEntryRepository;
import com.logmanager.service.LogPipelineService;
import com.logmanager.service.cache.Cache;
import com.logmanager.service.cache.MultiLevelCache;
import com.logmanager.service.pipeline.LogFilterChain;
import com.logmanager.service.pipeline.LogRouterChain;
import com.logmanager.service.pipeline.LogEnricherChain;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogPipelineServiceImpl implements LogPipelineService {

    private final LogEntryRepository logEntryRepository;
    private final EventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final LogFilterChain filterChain;
    private final LogRouterChain routerChain;
    private final LogEnricherChain enricherChain;

    @Qualifier("logEntryCache")
    private final Cache<String, LogEntry> logEntryCache;

    private final Sinks.Many<LogEntry> logSink = Sinks.many().multicast().onBackpressureBuffer();

    @PostConstruct
    public void init() {
        log.info("LogPipelineService initialized with {} filters, {} routers, {} enrichers",
                filterChain.size(), routerChain.size(), enricherChain.size());
    }

    @Override
    public Mono<LogEntry> collect(LogEntry logEntry) {
        LogEntry enriched = enricherChain.enrich(logEntry);

        if (!filterChain.doFilter(enriched)) {
            return Mono.empty();
        }

        return logEntryRepository.save(enriched)
                .flatMap(saved -> logEntryCache.put(saved.getId(), saved)
                        .thenReturn(saved))
                .doOnSuccess(saved -> {
                    logSink.tryEmitNext(saved);
                    meterRegistry.counter("logs.collected", "service", saved.getServiceName()).increment();
                    eventPublisher.publish(new DomainEvent("log.collected", saved.getId(), "log"));
                    routerChain.route(saved).subscribe(null, error -> log.error("Failed to route log", error));
                });
    }

    @Override
    public Flux<LogEntry> collectBatch(Flux<LogEntry> logEntries) {
        return logEntries.flatMap(this::collect);
    }

    @Override
    public Flux<LogEntry> filterByLevel(String serviceName, String level) {
        LogLevel logLevel = LogLevel.fromString(level);
        return logEntryRepository.findByServiceName(serviceName)
                .filter(entry -> entry.getLevel() != null && entry.getLevel().isHigherOrEqual(logLevel));
    }

    @Override
    public Flux<LogEntry> searchByTraceId(String traceId) {
        return logEntryCache.get(traceId)
                .flux()
                .switchIfEmpty(logEntryRepository.findByTraceId(traceId)
                        .flatMap(entry -> logEntryCache.put(entry.getId(), entry).thenReturn(entry)));
    }

    @Override
    public Mono<Map<String, Long>> getStats(String serviceName) {
        return logEntryRepository.countByServiceName(serviceName)
                .map(count -> {
                    Map<String, Long> stats = new HashMap<>();
                    stats.put("total", count);
                    return stats;
                });
    }

    @Override
    public Mono<Map<String, Object>> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        if (logEntryCache instanceof MultiLevelCache) {
            MultiLevelCache<String, LogEntry> mlCache = (MultiLevelCache<String, LogEntry>) logEntryCache;
            stats.put("warmupCount", mlCache.getWarmupCount());
            return mlCache.size()
                    .doOnNext(size -> stats.put("totalSize", size))
                    .then(mlCache.getL1Cache().size().doOnNext(size -> stats.put("l1Size", size)))
                    .then(mlCache.getL2Cache().size().doOnNext(size -> stats.put("l2Size", size)))
                    .thenReturn(stats);
        }
        return logEntryCache.size()
                .doOnNext(size -> stats.put("totalSize", size))
                .thenReturn(stats);
    }

    @Override
    public Mono<Void> invalidateCache(String traceId) {
        return logEntryCache.invalidate(traceId)
                .doOnSuccess(v -> log.info("Invalidated cache for traceId: {}", traceId));
    }

    @Override
    public Mono<Void> invalidateAllCache() {
        return logEntryCache.invalidateAll()
                .doOnSuccess(v -> log.info("Invalidated all log entry cache"));
    }

    @Override
    public Mono<Void> warmupCache() {
        if (logEntryCache instanceof MultiLevelCache) {
            return ((MultiLevelCache<String, LogEntry>) logEntryCache).warmup();
        }
        return Mono.empty();
    }
}
