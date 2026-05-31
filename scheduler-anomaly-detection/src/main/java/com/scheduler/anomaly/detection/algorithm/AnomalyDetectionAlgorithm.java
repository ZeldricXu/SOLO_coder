package com.scheduler.anomaly.detection.algorithm;

import com.scheduler.anomaly.detection.AnomalyResult;
import com.scheduler.anomaly.detection.model.MetricSeries;

public interface AnomalyDetectionAlgorithm {
    String getName();
    AnomalyResult detect(MetricSeries history, double currentValue);
    default boolean supports(String metricType) {
        return true;
    }
}
