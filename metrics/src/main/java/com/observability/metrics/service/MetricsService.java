package com.observability.metrics.service;

import com.observability.metrics.aggregator.MetricAggregator;
import com.observability.metrics.model.MetricAggregation;
import com.observability.metrics.model.MetricPoint;
import com.observability.metrics.storage.MetricStorage;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsService {

    private final List<MetricStorage> storages;
    private final List<MetricAggregator> aggregators;
    private final MeterRegistry meterRegistry;

    public Mono<Void> ingestMetric(MetricPoint point) {
        if (point.getTimestamp() == null) {
            point.setTimestamp(LocalDateTime.now());
        }
        return Mono.fromRunnable(() -> {
            for (MetricStorage storage : storages) {
                try {
                    storage.store(point);
                } catch (Exception e) {
                    log.error("Failed to store metric in {}", storage.getType(), e);
                }
            }

            try {
                meterRegistry.gauge(point.getName(), point.getValue());
            } catch (Exception e) {
                log.warn("Failed to register micrometer gauge", e);
            }
        });
    }

    public Mono<Map<String, Object>> aggregate(String metricName, Map<String, String> labels) {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = new HashMap<>();
            List<MetricPoint> points = queryPoints(metricName, labels);

            for (MetricAggregator aggregator : aggregators) {
                try {
                    result.put(aggregator.getName(), aggregator.aggregate(points));
                } catch (Exception e) {
                    log.error("Aggregator {} failed", aggregator.getName(), e);
                }
            }

            return result;
        });
    }

    public Mono<MetricAggregation> getAggregation(String metricName, int windowSeconds) {
        return Mono.fromCallable(() -> {
            MetricAggregation aggregation = new MetricAggregation();
            aggregation.setName(metricName);
            aggregation.setWindowStart(LocalDateTime.now().minusSeconds(windowSeconds));
            aggregation.setWindowEnd(LocalDateTime.now());
            return aggregation;
        });
    }

    public Mono<List<MetricPoint>> queryMetric(String metricName, Map<String, String> labels) {
        return Mono.fromCallable(() -> queryPoints(metricName, labels));
    }

    private List<MetricPoint> queryPoints(String metricName, Map<String, String> labels) {
        for (MetricStorage storage : storages) {
            try {
                List<MetricPoint> points = storage.query(metricName, 0, System.currentTimeMillis(), labels);
                if (points != null && !points.isEmpty()) {
                    return points;
                }
            } catch (Exception e) {
                log.error("Storage {} query failed", storage.getType(), e);
            }
        }
        return Collections.emptyList();
    }
}
