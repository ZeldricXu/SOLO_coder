package com.monitoring.storage.engine.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.monitoring.storage.engine.StorageEngine;
import com.monitoring.storage.model.TimeSeriesPoint;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class InMemoryStorageEngine implements StorageEngine {

    private final Cache<String, List<TimeSeriesPoint>> timeSeriesCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(100000)
            .build();

    private final AtomicLong totalPoints = new AtomicLong(0);
    private final AtomicLong writeCount = new AtomicLong(0);
    private final AtomicLong readCount = new AtomicLong(0);

    @Override
    public String getName() {
        return "in_memory";
    }

    @Override
    public Mono<Void> write(TimeSeriesPoint point) {
        return Mono.fromRunnable(() -> {
            String key = buildKey(point.getMetric(), point.getTags());
            timeSeriesCache.asMap().compute(key, (k, existing) -> {
                List<TimeSeriesPoint> points = existing != null ? existing : new ArrayList<>();
                points.add(point);
                if (points.size() > 10000) {
                    points = new ArrayList<>(points.subList(points.size() - 5000, points.size()));
                }
                return points;
            });
            totalPoints.incrementAndGet();
            writeCount.incrementAndGet();
        });
    }

    @Override
    public Mono<Void> writeBatch(List<TimeSeriesPoint> points) {
        return Flux.fromIterable(points)
                .flatMap(this::write)
                .then();
    }

    @Override
    public Flux<TimeSeriesPoint> read(String metric, Instant startTime, Instant endTime, Map<String, String> tags) {
        return Mono.fromSupplier(() -> {
            String key = buildKey(metric, tags);
            List<TimeSeriesPoint> points = timeSeriesCache.getIfPresent(key);
            if (points == null) {
                return Collections.<TimeSeriesPoint>emptyList();
            }
            long startMs = startTime.toEpochMilli();
            long endMs = endTime.toEpochMilli();
            return points.stream()
                    .filter(p -> p.getTimestamp() >= startMs && p.getTimestamp() < endMs)
                    .toList();
        })
                .doOnNext(list -> readCount.addAndGet(list.size()))
                .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<Map<String, Object>> getStats() {
        return Mono.fromSupplier(() -> {
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalPoints", totalPoints.get());
            stats.put("writeCount", writeCount.get());
            stats.put("readCount", readCount.get());
            stats.put("activeSeries", timeSeriesCache.estimatedSize());
            return stats;
        });
    }

    @Override
    public Mono<Void> compact() {
        return Mono.fromRunnable(timeSeriesCache::cleanUp);
    }

    @Override
    public Mono<Void> purge(Instant before) {
        return Mono.fromRunnable(() -> {
            long beforeMs = before.toEpochMilli();
            timeSeriesCache.asMap().values().forEach(points -> {
                points.removeIf(p -> p.getTimestamp() < beforeMs);
            });
            timeSeriesCache.asMap().entrySet().removeIf(e -> e.getValue().isEmpty());
        });
    }

    private String buildKey(String metric, Map<String, String> tags) {
        StringBuilder sb = new StringBuilder(metric);
        if (tags != null && !tags.isEmpty()) {
            TreeMap<String, String> sorted = new TreeMap<>(tags);
            sorted.forEach((k, v) -> sb.append('|').append(k).append('=').append(v));
        }
        return sb.toString();
    }
}
