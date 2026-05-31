package com.solo.config.module.metrics;

import com.solo.config.common.IdGenerator;
import com.solo.config.entity.MetricSnapshot;
import com.solo.config.mapper.MetricSnapshotMapper;
import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsService {

    private final MeterRegistry meterRegistry;
    private final MetricSnapshotMapper metricSnapshotMapper;

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, Gauge> gauges = new ConcurrentHashMap<>();

    public void incrementCounter(String name, String... tags) {
        String key = name + ":" + String.join(",", tags);
        AtomicLong counter = counters.computeIfAbsent(key, k -> {
            Counter.builder(name)
                    .tags(tags)
                    .register(meterRegistry);
            return new AtomicLong(0);
        });
        counter.incrementAndGet();
    }

    public void recordTimer(String name, long durationMs, String... tags) {
        Timer timer = timers.computeIfAbsent(name + ":" + String.join(",", tags), k ->
                Timer.builder(name)
                        .tags(tags)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meterRegistry)
        );
        timer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void setGauge(String name, double value, String... tags) {
        String key = name + ":" + String.join(",", tags);
        gauges.computeIfAbsent(key, k ->
                Gauge.builder(name, () -> value)
                        .tags(tags)
                        .register(meterRegistry)
        );
    }

    public Mono<Map<String, Object>> getMetrics() {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = new HashMap<>();

            for (Meter meter : meterRegistry.getMeters()) {
                String name = meter.getId().getName();
                if (meter instanceof Counter counter) {
                    result.put(name + ".count", counter.count());
                } else if (meter instanceof Timer timer) {
                    result.put(name + ".count", timer.count());
                    result.put(name + ".mean", timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
                    result.put(name + ".p95", timer.takeSnapshot().percentileValues()[1].value(java.util.concurrent.TimeUnit.MILLISECONDS));
                    result.put(name + ".p99", timer.takeSnapshot().percentileValues()[2].value(java.util.concurrent.TimeUnit.MILLISECONDS));
                } else if (meter instanceof Gauge gauge) {
                    result.put(name, gauge.value());
                }
            }

            return result;
        });
    }

    @Scheduled(fixedRate = 60000)
    public void takeSnapshot() {
        try {
            Map<String, Object> metrics = new HashMap<>();

            for (Meter meter : meterRegistry.getMeters()) {
                String name = meter.getId().getName();
                if (meter instanceof Counter counter) {
                    metrics.put(name + ".count", counter.count());
                } else if (meter instanceof Timer timer) {
                    metrics.put(name + ".count", timer.count());
                    metrics.put(name + ".mean_ms", timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
                } else if (meter instanceof Gauge gauge) {
                    metrics.put(name, gauge.value());
                }
            }

            Map<String, String> dimensions = new HashMap<>();
            dimensions.put("host", java.net.InetAddress.getLocalHost().getHostName());
            dimensions.put("region", "default");

            MetricSnapshot snapshot = new MetricSnapshot();
            snapshot.setSnapshotId(IdGenerator.generateSnapshotId());
            snapshot.setTimestamp(LocalDateTime.now());
            snapshot.setMetrics(metrics);
            snapshot.setDimensions(dimensions);

            metricSnapshotMapper.insert(snapshot);
            log.debug("Metrics snapshot taken, id: {}", snapshot.getSnapshotId());
        } catch (Exception e) {
            log.error("Failed to take metrics snapshot", e);
        }
    }

    public Flux<MetricSnapshot> listSnapshots(LocalDateTime startTime, LocalDateTime endTime) {
        return Flux.fromIterable(
                metricSnapshotMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MetricSnapshot>()
                                .ge(startTime != null, "timestamp", startTime)
                                .le(endTime != null, "timestamp", endTime)
                                .orderByDesc("timestamp")
                                .last("LIMIT 100")
                )
        );
    }
}
