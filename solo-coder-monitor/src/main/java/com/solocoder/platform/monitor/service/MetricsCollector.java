package com.solocoder.platform.monitor.service;

import com.solocoder.platform.monitor.model.MetricDataPoint;
import com.solocoder.platform.monitor.model.PerformanceSnapshot;

import java.util.List;
import java.util.Optional;

public interface MetricsCollector {

    void record(MetricDataPoint dataPoint);

    List<MetricDataPoint> query(String metricName, long startTimestamp, long endTimestamp);

    Optional<MetricDataPoint> latest(String metricName);

    PerformanceSnapshot capturePerformance();

    List<PerformanceSnapshot> getPerformanceHistory(int limit);
}
