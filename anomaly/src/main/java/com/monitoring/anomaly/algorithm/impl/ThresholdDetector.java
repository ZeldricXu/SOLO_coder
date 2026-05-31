package com.monitoring.anomaly.algorithm.impl;

import com.monitoring.anomaly.algorithm.AnomalyDetector;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ThresholdDetector implements AnomalyDetector {

    private static final String ALGORITHM_NAME = "threshold";

    @Override
    public String getName() {
        return ALGORITHM_NAME;
    }

    @Override
    public AnomalyResult detect(List<Double> historicalData, double currentValue, AnomalyConfig config) {
        double minThreshold = config.minThreshold();
        double maxThreshold = config.maxThreshold();

        boolean isAnomaly = currentValue < minThreshold || currentValue > maxThreshold;
        double deviation = calculateDeviation(currentValue, minThreshold, maxThreshold);
        double baseline = (minThreshold + maxThreshold) / 2.0;
        String message = buildMessage(isAnomaly, currentValue, minThreshold, maxThreshold);

        return new AnomalyResult(isAnomaly, deviation, maxThreshold, ALGORITHM_NAME, message, baseline, deviation);
    }

    private static double calculateDeviation(double currentValue, double minThreshold, double maxThreshold) {
        if (currentValue > maxThreshold) {
            return currentValue - maxThreshold;
        }
        if (currentValue < minThreshold) {
            return minThreshold - currentValue;
        }
        return 0.0;
    }

    private static String buildMessage(boolean isAnomaly, double currentValue, double minThreshold, double maxThreshold) {
        if (isAnomaly) {
            return String.format("Anomaly detected: value=%.2f out of range [%.2f, %.2f]",
                    currentValue, minThreshold, maxThreshold);
        }
        return String.format("Normal: value=%.2f within range [%.2f, %.2f]",
                currentValue, minThreshold, maxThreshold);
    }
}
