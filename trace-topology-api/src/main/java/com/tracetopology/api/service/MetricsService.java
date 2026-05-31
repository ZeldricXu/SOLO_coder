package com.tracetopology.api.service;

import com.tracetopology.domain.entity.Snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface MetricsService {

    void ingestMetric(String metricName, double value, Map<String, String> dimensions, long timestamp);

    void ingestMetrics(List<Map<String, Object>> metricsBatch);

    Snapshot createSnapshot(Map<String, String> dimensions);

    List<Snapshot> querySnapshots(String metricName, Instant startTime, Instant endTime,
                                   Map<String, String> dimensions);

    Map<String, Object> getAggregatedMetrics(String metricName, Instant startTime, Instant endTime,
                                              Map<String, String> dimensions, String aggregator);

    double getMetricValue(String metricName, Map<String, String> dimensions);
}
