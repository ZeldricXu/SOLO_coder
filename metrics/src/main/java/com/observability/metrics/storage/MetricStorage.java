package com.observability.metrics.storage;

import com.observability.metrics.model.MetricPoint;

import java.util.List;
import java.util.Map;

public interface MetricStorage {

    String getType();

    void store(MetricPoint point);

    List<MetricPoint> query(String metricName, long startTime, long endTime, Map<String, String> labels);
}
