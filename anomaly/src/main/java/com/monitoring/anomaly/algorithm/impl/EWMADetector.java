package com.monitoring.anomaly.algorithm.impl;

import com.monitoring.anomaly.algorithm.AnomalyDetector;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EWMADetector implements AnomalyDetector {

    private static final String ALGORITHM_NAME = "ewma";
    private static final double DEFAULT_ALPHA = 0.2;

    @Override
    public String getName() {
        return ALGORITHM_NAME;
    }

    @Override
    public AnomalyResult detect(List<Double> historicalData, double currentValue, AnomalyConfig config) {
        if (historicalData.isEmpty()) {
            return createNoDataResult();
        }

        double ewma = calculateEWMA(historicalData);
        double variance = calculateVariance(historicalData, ewma);
        double stdDev = Math.sqrt(variance);
        double threshold = stdDev * config.sensitivity();
        double lowerBound = ewma - threshold;
        double upperBound = ewma + threshold;

        boolean isAnomaly = currentValue < lowerBound || currentValue > upperBound;
        double deviation = Math.abs(currentValue - ewma);
        String message = buildMessage(isAnomaly, currentValue, lowerBound, upperBound);

        return new AnomalyResult(isAnomaly, deviation, upperBound, ALGORITHM_NAME, message, ewma, deviation);
    }

    private static double calculateEWMA(List<Double> data) {
        double ewma = data.get(0);
        for (int i = 1; i < data.size(); i++) {
            ewma = DEFAULT_ALPHA * data.get(i) + (1 - DEFAULT_ALPHA) * ewma;
        }
        return ewma;
    }

    private static double calculateVariance(List<Double> data, double mean) {
        double sumSquaredDiff = 0.0;
        int size = data.size();
        for (Double value : data) {
            double diff = value - mean;
            sumSquaredDiff += diff * diff;
        }
        return sumSquaredDiff / size;
    }

    private static String buildMessage(boolean isAnomaly, double currentValue, double lowerBound, double upperBound) {
        if (isAnomaly) {
            return String.format("Anomaly detected: value=%.2f outside EWMA bounds [%.2f, %.2f]",
                    currentValue, lowerBound, upperBound);
        }
        return String.format("Normal: value=%.2f within EWMA bounds [%.2f, %.2f]",
                currentValue, lowerBound, upperBound);
    }

    private static AnomalyResult createNoDataResult() {
        return new AnomalyResult(false, 0, 0, ALGORITHM_NAME, "No historical data", 0, 0);
    }
}
