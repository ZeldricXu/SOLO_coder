package com.logmanager.service.metrics;

import com.logmanager.domain.model.MetricsSnapshot;
import com.logmanager.domain.model.TimeSeriesMetric;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;

public interface MetricsRepository {
    Mono<Void> save(TimeSeriesMetric metric);

    Flux<TimeSeriesMetric> findAll();

    Flux<TimeSeriesMetric> findByMetricName(String metricName);

    Flux<TimeSeriesMetric> findByMetricNameAndTimeRange(String metricName, Instant start, Instant end);

    Flux<TimeSeriesMetric> findByServiceName(String serviceName);

    Mono<Void> saveSnapshot(MetricsSnapshot snapshot);

    Flux<MetricsSnapshot> findSnapshotsByTimeRange(Instant start, Instant end);
}
