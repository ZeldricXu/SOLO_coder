package com.logmanager.service.metrics.repository;

import com.logmanager.domain.model.MetricsSnapshot;
import com.logmanager.domain.model.TimeSeriesMetric;
import com.logmanager.service.metrics.MetricsRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryMetricsRepository implements MetricsRepository {

    private final Map<String, TimeSeriesMetric> metricStore = new ConcurrentHashMap<>();
    private final Map<String, MetricsSnapshot> snapshotStore = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> save(TimeSeriesMetric metric) {
        metricStore.put(metric.getMetricId(), metric);
        return Mono.empty();
    }

    @Override
    public Flux<TimeSeriesMetric> findAll() {
        return Flux.fromIterable(metricStore.values());
    }

    @Override
    public Flux<TimeSeriesMetric> findByMetricName(String metricName) {
        return Flux.fromIterable(metricStore.values())
                .filter(metric -> metricName.equals(metric.getMetricName()));
    }

    @Override
    public Flux<TimeSeriesMetric> findByMetricNameAndTimeRange(String metricName, Instant start, Instant end) {
        return findByMetricName(metricName)
                .filter(metric -> !metric.getTimestamp().isBefore(start) && !metric.getTimestamp().isAfter(end));
    }

    @Override
    public Flux<TimeSeriesMetric> findByServiceName(String serviceName) {
        return Flux.fromIterable(metricStore.values())
                .filter(metric -> metric.getLabels() != null && serviceName.equals(metric.getLabels().get("service")));
    }

    @Override
    public Mono<Void> saveSnapshot(MetricsSnapshot snapshot) {
        snapshotStore.put(snapshot.getSnapshotId(), snapshot);
        return Mono.empty();
    }

    @Override
    public Flux<MetricsSnapshot> findSnapshotsByTimeRange(Instant start, Instant end) {
        return Flux.fromIterable(snapshotStore.values())
                .filter(snapshot -> !snapshot.getTimestamp().isBefore(start) && !snapshot.getTimestamp().isAfter(end));
    }
}
