package com.monitoring.metrics.service;

import com.monitoring.metrics.aggregator.MetricAggregator;
import com.monitoring.metrics.model.MetricPoint;
import io.micrometer.core.instrument.*;
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
public class MetricsService {

    private final MeterRegistry meterRegistry;
    private final MetricAggregator aggregator;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Gauge<Double>> gauges = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> summaries = new ConcurrentHashMap<>();

    public Mono<Void> recordMetric(MetricPoint point) {
        return Mono.fromRunnable(() -> {
            if (point.getTimestamp() == null) {
                point.setTimestamp(Instant.now());
            }

            aggregator.aggregate(point);

            Tags tags = toTags(point.getDimensions());
            String name = sanitizeName(point.getName());

            Counter counter = counters.computeIfAbsent(name, k -> Counter.builder(k)
                    .tags(tags)
                    .register(meterRegistry));
            counter.increment(point.getValue());

            DistributionSummary summary = summaries.computeIfAbsent(name + ".summary", k -> DistributionSummary.builder(k)
                    .tags(tags)
                    .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                    .register(meterRegistry));
            summary.record(point.getValue());

            log.debug("Recorded metric: {}={}", point.getName(), point.getValue());
        });
    }

    public Mono<Void> recordMetrics(List<MetricPoint> points) {
        return Flux.fromIterable(points)
                .flatMap(this::recordMetric)
                .then();
    }

    public Mono<Void> recordCounter(String name, double value, Map<String, String> dimensions) {
        return Mono.fromRunnable(() -> {
            Tags tags = toTags(dimensions);
            Counter counter = counters.computeIfAbsent(name, k -> Counter.builder(k)
                    .tags(tags)
                    .register(meterRegistry));
            counter.increment(value);
        });
    }

    public Mono<Void> recordGauge(String name, double value, Map<String, String> dimensions) {
        return Mono.fromRunnable(() -> {
            Tags tags = toTags(dimensions);
            String key = name + tags.toString();
            gauges.computeIfAbsent(key, k -> {
                AtomicDouble valueHolder = new AtomicDouble(value);
                return Gauge.builder(name, valueHolder, AtomicDouble::get)
                        .tags(tags)
                        .register(meterRegistry);
            });
        });
    }

    public Mono<Void> recordTimer(String name, Duration duration, Map<String, String> dimensions) {
        return Mono.fromRunnable(() -> {
            Tags tags = toTags(dimensions);
            Timer timer = timers.computeIfAbsent(name, k -> Timer.builder(k)
                    .tags(tags)
                    .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                    .register(meterRegistry));
            timer.record(duration);
        });
    }

    public Mono<Map<String, Object>> getMetricStats(String name) {
        return Mono.fromSupplier(() -> {
            List<MetricAggregator.AggregatedMetric> aggs = aggregator.getAggregationsByName(name);
            if (aggs.isEmpty()) {
                return Collections.emptyMap();
            }
            return aggs.get(0).toMap();
        });
    }

    public Mono<List<Map<String, Object>>> getAllMetricStats() {
        return Mono.fromSupplier(() ->
                aggregator.getAllAggregations().stream()
                        .map(MetricAggregator.AggregatedMetric::toMap)
                        .toList()
        );
    }

    public Mono<Map<String, Object>> getMeterStats() {
        return Mono.fromSupplier(() -> {
            Map<String, Object> stats = new HashMap<>();
            stats.put("counters", counters.size());
            stats.put("gauges", gauges.size());
            stats.put("timers", timers.size());
            stats.put("summaries", summaries.size());
            stats.put("aggregations", aggregator.getAllAggregations().size());
            return stats;
        });
    }

    public void resetAll() {
        aggregator.reset();
        counters.clear();
        gauges.clear();
        timers.clear();
        summaries.clear();
        meterRegistry.clear();
        log.info("All metrics reset");
    }

    private Tags toTags(Map<String, String> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return Tags.empty();
        }
        List<Tag> tags = new ArrayList<>();
        dimensions.forEach((k, v) -> tags.add(Tag.of(k, v)));
        return Tags.of(tags);
    }

    private String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static class AtomicDouble {
        private volatile double value;

        public AtomicDouble(double value) {
            this.value = value;
        }

        public double get() {
            return value;
        }

        public void set(double value) {
            this.value = value;
        }
    }
}
