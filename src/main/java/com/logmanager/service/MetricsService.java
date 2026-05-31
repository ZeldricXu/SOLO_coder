package com.logmanager.service;

import com.logmanager.domain.model.MetricsSnapshot;
import com.logmanager.domain.model.TimeSeriesMetric;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface MetricsService {
    Mono<TimeSeriesMetric> recordMetric(String metricName, Double value, Map<String, String> labels);
    Flux<TimeSeriesMetric> recordMetrics(Flux<TimeSeriesMetric> metrics);
    Mono<MetricsSnapshot> createSnapshot();
    Flux<MetricsSnapshot> getSnapshots(Instant start, Instant end);
    Mono<Map<String, Object>> queryMetrics(String metricName, Instant start, Instant end, Map<String, String> labels);
    Flux<TimeSeriesMetric> getMetricsByService(String serviceName);

    Mono<Void> recordMetricAsync(String metricName, Double value, Map<String, String> labels);
    Mono<List<TimeSeriesMetric>> recordMetricsBatch(List<TimeSeriesMetric> metrics);
    Mono<Map<String, Object>> getBatchStats();
}
