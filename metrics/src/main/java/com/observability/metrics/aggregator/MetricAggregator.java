package com.observability.metrics.aggregator;

import com.observability.metrics.model.MetricPoint;

import java.util.List;
import java.util.Map;

public interface MetricAggregator {

    String getName();

    Map<String, Object> aggregate(List<MetricPoint> points);
}
