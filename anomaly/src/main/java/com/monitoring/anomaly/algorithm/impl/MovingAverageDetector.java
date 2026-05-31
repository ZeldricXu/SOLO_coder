package com.monitoring.anomaly.algorithm.impl;

import com.monitoring.anomaly.algorithm.AnomalyDetector;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MovingAverageDetector implements AnomalyDetector {

    private static final String ALGORITHM_NAME = "ma";
    private static final int MIN_WINDOW_SIZE = 2;

    @Override
    public String getName() {
        return ALGORITHM_NAME;
    }

    @Override
    public AnomalyResult detect(List<Double> historicalData, double currentValue, AnomalyConfig config) {
        int dataSize = historicalData.size();
        int windowSize = Math.min(config.windowSize(), dataSize);

        if (windowSize < MIN_WINDOW_SIZE) {
            return createInsufficientDataResult();
        }

        double movingAvg = calculateMovingAverage(historicalData, dataSize, windowSize);
        double stdDev = calculateWindowStdDev(historicalData, dataSize, windowSize, movingAvg);
        double multiplier = config.sensitivity() * 0.5 + 1.0;
        double lowerBound = movingAvg - stdDev * multiplier;
        double upperBound = movingAvg + stdDev * multiplier;

        boolean isAnomaly = currentValue < lowerBound || currentValue > upperBound;
        double deviation = Math.abs(currentValue - movingAvg);
        String message = buildMessage(isAnomaly, currentValue, lowerBound, upperBound);

        return new AnomalyResult(isAnomaly, deviation, upperBound, ALGORITHM_NAME, message, movingAvg, deviation);
    }

    private static double calculateMovingAverage(List<Double> data, int dataSize, int windowSize) {
        double sum = 0.0;
        int startIndex = dataSize - windowSize;
        for (int i = startIndex; i < dataSize; i++) {
            sum += data.get(i);
        }
        return sum / windowSize;
    }

    private static double calculateWindowStdDev(List<Double> data, int dataSize, int windowSize, double mean) {
        double sumSquaredDiff = 0.0;
        int startIndex = dataSize - windowSize;
        for (int i = startIndex; i < dataSize; i++) {
            double diff = data.get(i) - mean;
            sumSquaredDiff += diff * diff;
        }
        return Math.sqrt(sumSquaredDiff / windowSize);
    }

    private static String buildMessage(boolean isAnomaly, double currentValue, double lowerBound, double upperBound) {
        if (isAnomaly) {
            return String.format("Anomaly detected: value=%.2f outside MA bounds [%.2f, %.2f]",
                    currentValue, lowerBound, upperBound);
        }
        return String.format("Normal: value=%.2f within MA bounds [%.2f, %.2f]",
                currentValue, lowerBound, upperBound);
    }

    private static AnomalyResult createInsufficientDataResult() {
        return new AnomalyResult(false, 0, 0, ALGORITHM_NAME, "Insufficient data for moving average", 0, 0);
    }
}
