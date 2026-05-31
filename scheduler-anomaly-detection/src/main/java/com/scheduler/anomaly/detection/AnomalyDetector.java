package com.scheduler.anomaly.detection;

import com.scheduler.persistence.entity.MetricsSnapshot;
import java.util.List;
import java.util.Map;

public interface AnomalyDetector {
    String getAlgorithmName();
    AnomalyResult detect(List<MetricsSnapshot> historicalData, MetricsSnapshot currentData);
    boolean supports(String metricType);
}
