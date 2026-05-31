package com.scheduler.anomaly.detection.algorithm.impl;

import com.scheduler.anomaly.detection.AnomalyResult;
import com.scheduler.anomaly.detection.algorithm.AnomalyDetectionAlgorithm;
import com.scheduler.anomaly.detection.model.MetricSeries;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ThresholdAlgorithm implements AnomalyDetectionAlgorithm {

    private static final Map<String, double[]> THRESHOLDS = Map.of(
            "error_rate", new double[]{0.01, 0.05, 0.1},
            "latency_p99", new double[]{100, 500, 1000},
            "throughput", new double[]{100, 50, 10}
    );

    @Override
    public String getName() {
        return "THRESHOLD";
    }

    @Override
    public AnomalyResult detect(MetricSeries history, double currentValue) {
        String metricName = history.getMetricName();
        double[] thresholds = THRESHOLDS.get(metricName);

        if (thresholds == null) {
            return AnomalyResult.normal(metricName, currentValue, getName());
        }

        String severity = null;
        String description = null;
        double expectedValue = thresholds[1];

        if ("throughput".equals(metricName)) {
            if (currentValue < thresholds[2]) {
                severity = "CRITICAL";
                description = String.format("Throughput %.0f is critically low (threshold: %.0f)",
                        currentValue, thresholds[2]);
            } else if (currentValue < thresholds[1]) {
                severity = "WARNING";
                description = String.format("Throughput %.0f is below warning threshold (threshold: %.0f)",
                        currentValue, thresholds[1]);
            }
        } else {
            if (currentValue > thresholds[2]) {
                severity = "CRITICAL";
                description = String.format("%s %.2f exceeds critical threshold (threshold: %.2f)",
                        metricName, currentValue, thresholds[2]);
            } else if (currentValue > thresholds[1]) {
                severity = "WARNING";
                description = String.format("%s %.2f exceeds warning threshold (threshold: %.2f)",
                        metricName, currentValue, thresholds[1]);
            }
        }

        if (severity != null) {
            return AnomalyResult.anomaly(metricName, currentValue, expectedValue, severity, getName(), description);
        }

        return AnomalyResult.normal(metricName, currentValue, getName());
    }
}
