package com.monitoring.anomaly.algorithm.impl;

import com.monitoring.anomaly.algorithm.AnomalyDetector;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ZScoreDetector implements AnomalyDetector {

    private static final String ALGORITHM_NAME = "zscore";
    private static final int MIN_HISTORY_SIZE = 2;

    @Override
    public String getName() {
        return ALGORITHM_NAME;
    }

    @Override
    public AnomalyResult detect(List<Double> historicalData, double currentValue, AnomalyConfig config) {
        int size = historicalData.size();
        if (size < MIN_HISTORY_SIZE) {
            return createInsufficientDataResult();
        }

        double mean = calculateMean(historicalData, size);
        double stdDev = calculateStdDev(historicalData, mean, size);

        if (stdDev == 0.0) {
            return createNoVarianceResult(mean, config.minThreshold());
        }

        double zScore = (currentValue - mean) / stdDev;
        double threshold = config.sensitivity();
        double deviation = Math.abs(zScore);
        boolean isAnomaly = isAnomaly(currentValue, deviation, threshold, config);
        String message = buildMessage(isAnomaly, zScore, threshold, currentValue);

        return new AnomalyResult(isAnomaly, zScore, threshold, ALGORITHM_NAME, message, mean, deviation);
    }

    private static double calculateMean(List<Double> data, int size) {
        double sum = 0.0;
        for (Double value : data) {
            sum += value;
        }
        return sum / size;
    }

    private static double calculateStdDev(List<Double> data, double mean, int size) {
        double sumSquaredDiff = 0.0;
        for (Double value : data) {
            double diff = value - mean;
            sumSquaredDiff += diff * diff;
        }
        return Math.sqrt(sumSquaredDiff / size);
    }

    private static boolean isAnomaly(double currentValue, double deviation, double threshold, AnomalyConfig config) {
        return deviation > threshold
                && (currentValue < config.minThreshold() || currentValue > config.maxThreshold());
    }

    private static String buildMessage(boolean isAnomaly, double zScore, double threshold, double currentValue) {
        if (isAnomaly) {
            return String.format("Anomaly detected: z-score=%.2f, threshold=%.2f, value=%.2f",
                    zScore, threshold, currentValue);
        }
        return String.format("Normal: z-score=%.2f, threshold=%.2f", zScore, threshold);
    }

    private static AnomalyResult createInsufficientDataResult() {
        return new AnomalyResult(false, 0, 0, ALGORITHM_NAME, "Insufficient historical data", 0, 0);
    }

    private static AnomalyResult createNoVarianceResult(double mean, double minThreshold) {
        return new AnomalyResult(false, 0, minThreshold, ALGORITHM_NAME, "No variance in data", mean, 0);
    }
}
