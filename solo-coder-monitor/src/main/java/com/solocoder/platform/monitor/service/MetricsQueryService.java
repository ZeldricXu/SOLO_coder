package com.solocoder.platform.monitor.service;

import com.solocoder.platform.monitor.model.MetricDataPoint;
import com.solocoder.platform.monitor.model.PerformanceSnapshot;

import java.util.List;

public interface MetricsQueryService {

    List<MetricDataPoint> queryMetrics(String metricName, long startTimestamp, long endTimestamp);

    MetricDataPoint getLatestMetric(String metricName);

    PerformanceSnapshot getCurrentPerformance();

    List<PerformanceSnapshot> getPerformanceHistory(int limit);
}
