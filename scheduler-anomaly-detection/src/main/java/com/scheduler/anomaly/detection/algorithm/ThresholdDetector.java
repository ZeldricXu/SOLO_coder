package com.scheduler.anomaly.detection.algorithm;

import com.scheduler.anomaly.detection.AnomalyDetector;
import com.scheduler.anomaly.detection.AnomalyResult;
import com.scheduler.persistence.entity.MetricsSnapshot;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class ThresholdDetector implements AnomalyDetector {

    private static final Map<String, double[]> THRESHOLDS = Map.of(
            "error_rate", new double[]{0.01, 0.05, 0.1},
            "latency_p99", new double[]{100, 500, 1000},
            "throughput", new double[]{100, 50, 10}
    );

    @Override
    public String getAlgorithmName() {
        return "THRESHOLD";
    }

    @Override
    public AnomalyResult detect(List<MetricsSnapshot> historicalData, MetricsSnapshot currentData) {
        Map<String, Object> metrics = currentData.getMetrics();
        for (Map.Entry<String, double[]> entry : THRESHOLDS.entrySet()) {
            String metricName = entry.getKey();
            Object valueObj = metrics.get(metricName);
            if (valueObj instanceof Number) {
                double value = ((Number) valueObj).doubleValue();
                double[] thresholds = entry.getValue();
                String severity = null;
                String description = null;

                if ("throughput".equals(metricName)) {
                    if (value < thresholds[2]) {
                        severity = "CRITICAL";
                        description = String.format("Throughput %.0f is critically low (threshold: %.0f)", value, thresholds[2]);
                    } else if (value < thresholds[1]) {
                        severity = "WARNING";
                        description = String.format("Throughput %.0f is below warning threshold (threshold: %.0f)", value, thresholds[1]);
                    }
                } else {
                    if (value > thresholds[2]) {
                        severity = "CRITICAL";
                        description = String.format("%s %.2f exceeds critical threshold (threshold: %.2f)", metricName, value, thresholds[2]);
                    } else if (value > thresholds[1]) {
                        severity = "WARNING";
                        description = String.format("%s %.2f exceeds warning threshold (threshold: %.2f)", metricName, value, thresholds[1]);
                    }
                }

                if (severity != null) {
                    return AnomalyResult.anomaly(metricName, value, thresholds[1], severity, getAlgorithmName(), description);
                }
            }
        }
        return AnomalyResult.normal("all", 0, getAlgorithmName());
    }

    @Override
    public boolean supports(String metricType) {
        return true;
    }
}
