package com.logmanager.service.metrics;

import com.logmanager.domain.model.MetricsSnapshot;
import com.logmanager.domain.model.TimeSeriesMetric;
import reactor.core.publisher.Mono;
import java.util.Map;

public interface MetricsAggregator {
    Mono<Map<String, Object>> aggregate(String metricName, Iterable<TimeSeriesMetric> metrics);

    Mono<MetricsSnapshot> createSnapshot(Iterable<TimeSeriesMetric> metrics);
}
