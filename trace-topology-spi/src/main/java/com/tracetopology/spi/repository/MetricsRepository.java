package com.tracetopology.spi.repository;

import com.tracetopology.domain.entity.Snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface MetricsRepository {

    void saveMetric(String metricName, double value, Map<String, String> dimensions, long timestamp);

    void saveMetricsBatch(List<Map<String, Object>> metricsBatch);

    Snapshot saveSnapshot(Snapshot snapshot);

    Optional<Snapshot> findSnapshotById(String snapshotId);

    List<Snapshot> findSnapshots(String metricName, Instant startTime, Instant endTime,
                                  Map<String, String> dimensions);

    double getCurrentMetricValue(String metricName, Map<String, String> dimensions);

    Map<String, Object> aggregateMetrics(String metricName, Instant startTime, Instant endTime,
                                          Map<String, String> dimensions, String aggregator);

    List<Map<String, Object>> getMetricHistory(String metricName, Map<String, String> dimensions,
                                                int points);
}
