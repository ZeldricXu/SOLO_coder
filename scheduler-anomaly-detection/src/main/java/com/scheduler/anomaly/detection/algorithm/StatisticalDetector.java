package com.scheduler.anomaly.detection.algorithm;

import com.scheduler.anomaly.detection.AnomalyDetector;
import com.scheduler.anomaly.detection.AnomalyResult;
import com.scheduler.persistence.entity.MetricsSnapshot;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class StatisticalDetector implements AnomalyDetector {

    private static final double Z_SCORE_THRESHOLD = 3.0;

    @Override
    public String getAlgorithmName() {
        return "STATISTICAL_ZSCORE";
    }

    @Override
    public AnomalyResult detect(List<MetricsSnapshot> historicalData, MetricsSnapshot currentData) {
        if (historicalData.size() < 10) {
            return AnomalyResult.normal("insufficient_data", 0, getAlgorithmName());
        }

        Map<String, Object> currentMetrics = currentData.getMetrics();

        for (String metricName : currentMetrics.keySet()) {
            Object valueObj = currentMetrics.get(metricName);
            if (!(valueObj instanceof Number)) continue;
            double currentValue = ((Number) valueObj).doubleValue();

            DescriptiveStatistics stats = new DescriptiveStatistics();
            for (MetricsSnapshot snapshot : historicalData) {
                Object histValueObj = snapshot.getMetrics().get(metricName);
                if (histValueObj instanceof Number) {
                    stats.addValue(((Number) histValueObj).doubleValue());
                }
            }

            if (stats.getN() < 5) continue;

            double mean = stats.getMean();
            double std = stats.getStandardDeviation();
            if (std == 0) continue;

            double zScore = Math.abs((currentValue - mean) / std);

            if (zScore > Z_SCORE_THRESHOLD) {
                String severity = zScore > 4 ? "CRITICAL" : "WARNING";
                String description = String.format("Z-score %.2f exceeds threshold %.2f (mean=%.2f, std=%.2f)",
                        zScore, Z_SCORE_THRESHOLD, mean, std);
                return AnomalyResult.anomaly(metricName, currentValue, mean, severity, getAlgorithmName(), description);
            }
        }

        return AnomalyResult.normal("all", 0, getAlgorithmName());
    }

    @Override
    public boolean supports(String metricType) {
        return true;
    }
}
