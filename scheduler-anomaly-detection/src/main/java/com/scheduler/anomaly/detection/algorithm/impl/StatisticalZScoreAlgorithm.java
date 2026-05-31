package com.scheduler.anomaly.detection.algorithm.impl;

import com.scheduler.anomaly.detection.AnomalyResult;
import com.scheduler.anomaly.detection.algorithm.AnomalyDetectionAlgorithm;
import com.scheduler.anomaly.detection.model.MetricSeries;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.stereotype.Component;

@Component
public class StatisticalZScoreAlgorithm implements AnomalyDetectionAlgorithm {

    private static final double Z_SCORE_THRESHOLD = 3.0;
    private static final int MIN_DATA_POINTS = 5;

    @Override
    public String getName() {
        return "STATISTICAL_ZSCORE";
    }

    @Override
    public AnomalyResult detect(MetricSeries history, double currentValue) {
        String metricName = history.getMetricName();

        if (history.size() < MIN_DATA_POINTS) {
            return AnomalyResult.normal(metricName, currentValue, getName())
                    .setDescription("Insufficient data points for statistical analysis");
        }

        DescriptiveStatistics stats = new DescriptiveStatistics(history.toDoubleArray());
        double mean = stats.getMean();
        double std = stats.getStandardDeviation();

        if (std == 0) {
            return AnomalyResult.normal(metricName, currentValue, getName())
                    .setDescription("Zero standard deviation in historical data");
        }

        double zScore = Math.abs((currentValue - mean) / std);

        if (zScore > Z_SCORE_THRESHOLD) {
            String severity = zScore > 4 ? "CRITICAL" : "WARNING";
            String description = String.format("Z-score %.2f exceeds threshold %.2f (mean=%.2f, std=%.2f)",
                    zScore, Z_SCORE_THRESHOLD, mean, std);
            return AnomalyResult.anomaly(metricName, currentValue, mean, severity, getName(), description);
        }

        return AnomalyResult.normal(metricName, currentValue, getName());
    }
}
