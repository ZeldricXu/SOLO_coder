package com.monitoring.storage.service;

import com.monitoring.common.utils.JsonUtils;
import com.monitoring.dal.repository.MetricDataRepository;
import com.monitoring.persistence.entity.MetricDataDO;
import com.monitoring.storage.engine.StorageEngine;
import com.monitoring.storage.model.TimeSeriesPoint;
import com.monitoring.storage.preaggregator.PreAggregator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TimeSeriesService {

    private final Map<String, StorageEngine> engines = new ConcurrentHashMap<>();
    private final PreAggregator preAggregator;
    private final MetricDataRepository metricDataRepository;

    private final RateLimiter rateLimiter = new RateLimiter(10000, Duration.ofSeconds(1));

    public void registerEngine(StorageEngine engine) {
        engines.put(engine.getName(), engine);
    }

    public Mono<Void> ingest(TimeSeriesPoint point) {
        if (!rateLimiter.tryAcquire()) {
            return Mono.error(new RuntimeException("Rate limit exceeded for metrics ingestion"));
        }

        if (point.getTimestamp() == 0) {
            point.setTimestamp(System.currentTimeMillis());
        }

        preAggregator.aggregate(point);

        MetricDataDO metricDO = MetricDataDO.builder()
                .metricName(point.getMetric())
                .value(point.getValue())
                .dimensions(JsonUtils.toJson(point.getTags()))
                .timestamp(point.getTimestampAsInstant())
                .timestampHour(point.getTimestamp() / 3600000)
                .timestampDay(point.getTimestamp() / 86400000)
                .createdAt(Instant.now())
                .build();

        try {
            metricDataRepository.save(metricDO);
        } catch (Exception e) {
            log.warn("Failed to persist metric data: {}", e.getMessage());
        }

        return Flux.fromIterable(engines.values())
                .flatMap(engine -> engine.write(point)
                        .onErrorResume(e -> {
                            log.error("Storage engine {} write failed: {}", engine.getName(), e.getMessage());
                            return Mono.empty();
                        }))
                .then();
    }

    public Mono<Void> ingestBatch(List<TimeSeriesPoint> points) {
        return Flux.fromIterable(points)
                .flatMap(this::ingest)
                .then();
    }

    public Flux<TimeSeriesPoint> query(String metric, Instant startTime, Instant endTime,
                                       Map<String, String> tags, String engineName) {
        StorageEngine engine = engines.getOrDefault(engineName, engines.get("in_memory"));
        if (engine == null) {
            return Flux.empty();
        }
        return engine.read(metric, startTime, endTime, tags);
    }

    public Flux<TimeSeriesPoint> queryWithPreAggregation(String metric, Instant startTime, Instant endTime,
                                                          Map<String, String> tags, String granularity) {
        List<TimeSeriesPoint> preAggregated = switch (granularity.toLowerCase()) {
            case "minute" -> preAggregator.getMinuteAggregations(metric, startTime, endTime);
            case "hour" -> preAggregator.getHourAggregations(metric, startTime, endTime);
            default -> Collections.emptyList();
        };

        if (!preAggregated.isEmpty()) {
            return Flux.fromIterable(preAggregated);
        }

        return query(metric, startTime, endTime, tags, "in_memory");
    }

    public Mono<Map<String, Object>> getStats() {
        return Mono.fromSupplier(() -> {
            Map<String, Object> stats = new HashMap<>();
            stats.put("preAggregator", preAggregator.getStats());
            engines.forEach((name, engine) -> {
                try {
                    stats.put(name, engine.getStats().block());
                } catch (Exception e) {
                    log.warn("Failed to get stats from engine {}: {}", name, e.getMessage());
                }
            });
            return stats;
        });
    }

    public Mono<Void> compact() {
        return Flux.fromIterable(engines.values())
                .flatMap(StorageEngine::compact)
                .then();
    }

    public Mono<Void> purgeDataOlderThan(Duration age) {
        Instant threshold = Instant.now().minus(age);
        preAggregator.purgeOld(age);
        return Flux.fromIterable(engines.values())
                .flatMap(engine -> engine.purge(threshold))
                .then();
    }

    private static class RateLimiter {
        private final long maxPermits;
        private final Duration period;
        private long lastRefillTime;
        private long availablePermits;

        public RateLimiter(long maxPermits, Duration period) {
            this.maxPermits = maxPermits;
            this.period = period;
            this.lastRefillTime = System.currentTimeMillis();
            this.availablePermits = maxPermits;
        }

        public synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;

            if (elapsed >= period.toMillis()) {
                availablePermits = maxPermits;
                lastRefillTime = now;
            }

            if (availablePermits > 0) {
                availablePermits--;
                return true;
            }
            return false;
        }
    }
}
