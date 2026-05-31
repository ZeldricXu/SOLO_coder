package com.logmanager.metrics;

import com.logmanager.domain.model.TimeSeriesMetric;
import com.logmanager.service.metrics.aggregator.DefaultMetricsAggregator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultMetricsAggregatorTest {

    private DefaultMetricsAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new DefaultMetricsAggregator();
    }

    @Test
    void shouldAggregateMetricsCorrectly() {
        List<TimeSeriesMetric> metrics = Arrays.asList(
                createMetric("request.latency", 100.0),
                createMetric("request.latency", 200.0),
                createMetric("request.latency", 300.0)
        );

        Mono<Map<String, Object>> resultMono = aggregator.aggregate("request.latency", metrics);

        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertEquals(600.0, result.get("sum"));
                    assertEquals(3, result.get("count"));
                    assertEquals(200.0, result.get("avg"));
                    assertEquals(300.0, result.get("max"));
                    assertEquals(100.0, result.get("min"));
                })
                .verifyComplete();
    }

    @Test
    void shouldHandleEmptyMetrics() {
        List<TimeSeriesMetric> metrics = Collections.emptyList();

        Mono<Map<String, Object>> resultMono = aggregator.aggregate("request.latency", metrics);

        StepVerifier.create(resultMono)
                .assertNext(result -> {
                    assertEquals(0.0, result.get("sum"));
                    assertEquals(0, result.get("count"));
                    assertEquals(0, result.get("avg"));
                    assertNull(result.get("max"));
                    assertNull(result.get("min"));
                })
                .verifyComplete();
    }

    @Test
    void shouldCreateSnapshot() {
        List<TimeSeriesMetric> metrics = Arrays.asList(
                createMetric("request.count", 10.0),
                createMetric("request.count", 20.0),
                createMetric("error.count", 5.0)
        );

        StepVerifier.create(aggregator.createSnapshot(metrics))
                .assertNext(snapshot -> {
                    assertNotNull(snapshot.getSnapshotId());
                    assertNotNull(snapshot.getTimestamp());
                    assertEquals(2, snapshot.getMetrics().size());
                    assertEquals(30.0, snapshot.getMetrics().get("request.count"));
                    assertEquals(5.0, snapshot.getMetrics().get("error.count"));
                })
                .verifyComplete();
    }

    private TimeSeriesMetric createMetric(String name, Double value) {
        TimeSeriesMetric metric = new TimeSeriesMetric();
        metric.setMetricId(UUID.randomUUID().toString());
        metric.setMetricName(name);
        metric.setValue(value);
        metric.setTimestamp(Instant.now());
        return metric;
    }
}
