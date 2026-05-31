package com.monitoring.anomaly.algorithm;

import com.monitoring.common.model.MetricsSnapshot;

import java.util.List;

public interface AnomalyDetector {

    String getName();

    AnomalyResult detect(List<Double> historicalData, double currentValue, AnomalyConfig config);

    default AnomalyResult detect(MetricsSnapshot snapshot, List<MetricsSnapshot> history, AnomalyConfig config) {
        List<Double> values = history.stream()
                .flatMap(s -> s.getMetrics().values().stream())
                .toList();
        double currentValue = snapshot.getMetrics().values().stream().mapToDouble(Double::doubleValue).sum();
        return detect(values, currentValue, config);
    }

    record AnomalyResult(
            boolean isAnomaly,
            double score,
            double threshold,
            String algorithm,
            String message,
            double baseline,
            double deviation
    ) {}

    record AnomalyConfig(
            double sensitivity,
            int windowSize,
            double minThreshold,
            double maxThreshold
    ) {}
}
