package com.solocoder.platform.monitor.service.impl;

import com.solocoder.platform.monitor.model.MetricDataPoint;
import com.solocoder.platform.monitor.model.PerformanceSnapshot;
import com.solocoder.platform.monitor.service.MetricsCollector;
import com.solocoder.platform.monitor.service.MetricsQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MetricsQueryServiceImpl implements MetricsQueryService {

    private final MetricsCollector metricsCollector;

    @Override
    public List<MetricDataPoint> queryMetrics(String metricName, long startTimestamp, long endTimestamp) {
        return metricsCollector.query(metricName, startTimestamp, endTimestamp);
    }

    @Override
    public MetricDataPoint getLatestMetric(String metricName) {
        return metricsCollector.latest(metricName).orElse(null);
    }

    @Override
    public PerformanceSnapshot getCurrentPerformance() {
        return metricsCollector.capturePerformance();
    }

    @Override
    public List<PerformanceSnapshot> getPerformanceHistory(int limit) {
        return metricsCollector.getPerformanceHistory(limit);
    }
}
