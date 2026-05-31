package com.logmanager.service.metrics.aggregator;

import com.logmanager.domain.model.MetricsSnapshot;
import com.logmanager.domain.model.TimeSeriesMetric;
import com.logmanager.service.metrics.MetricsAggregator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class DefaultMetricsAggregator implements MetricsAggregator {

    @Override
    public Mono<Map<String, Object>> aggregate(String metricName, Iterable<TimeSeriesMetric> metrics) {
        Map<String, Object> result = new HashMap<>();
        Double sum = 0.0;
        int count = 0;
        Double max = Double.MIN_VALUE;
        Double min = Double.MAX_VALUE;

        for (TimeSeriesMetric metric : metrics) {
            sum += metric.getValue();
            count++;
            max = Math.max(max, metric.getValue());
            min = Math.min(min, metric.getValue());
        }

        result.put("sum", sum);
        result.put("count", count);
        result.put("avg", count > 0 ? sum / count : 0);
        result.put("max", max == Double.MIN_VALUE ? null : max);
        result.put("min", min == Double.MAX_VALUE ? null : min);

        return Mono.just(result);
    }

    @Override
    public Mono<MetricsSnapshot> createSnapshot(Iterable<TimeSeriesMetric> metrics) {
        MetricsSnapshot snapshot = new MetricsSnapshot();
        snapshot.setId(UUID.randomUUID().toString());
        snapshot.setSnapshotId(UUID.randomUUID().toString());
        snapshot.setTimestamp(Instant.now());
        snapshot.setCreatedAt(Instant.now());

        Map<String, Double> aggregatedMetrics = new HashMap<>();
        for (TimeSeriesMetric metric : metrics) {
            String key = metric.getMetricName();
            aggregatedMetrics.merge(key, metric.getValue(), Double::sum);
        }
        snapshot.setMetrics(aggregatedMetrics);
        snapshot.setDimensions(new HashMap<>());

        return Mono.just(snapshot);
    }
}
