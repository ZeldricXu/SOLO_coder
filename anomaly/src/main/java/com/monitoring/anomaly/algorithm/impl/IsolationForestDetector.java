package com.monitoring.anomaly.algorithm.impl;

import com.monitoring.anomaly.algorithm.AnomalyDetector;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class IsolationForestDetector implements AnomalyDetector {

    private static final String ALGORITHM_NAME = "isolation_forest";
    private static final int NUM_TREES = 100;
    private static final int SUBSAMPLE_SIZE = 256;
    private static final int MIN_HISTORY_SIZE = 10;
    private static final int MAX_TREE_DEPTH = 20;
    private static final double MIN_SPLIT_RANGE = 0.0001;

    @Override
    public String getName() {
        return ALGORITHM_NAME;
    }

    @Override
    public AnomalyResult detect(List<Double> historicalData, double currentValue, AnomalyConfig config) {
        int historySize = historicalData.size();
        if (historySize < MIN_HISTORY_SIZE) {
            return createInsufficientDataResult();
        }

        double[] allData = toDoubleArray(historicalData, currentValue);
        int totalSize = allData.length;
        double[] anomalyScores = new double[totalSize];

        buildIsolationForest(allData, anomalyScores);

        normalizeScores(anomalyScores);

        double currentScore = anomalyScores[totalSize - 1];
        double threshold = calculateThreshold(anomalyScores, config.sensitivity());
        double meanScore = calculateMean(anomalyScores);
        boolean isAnomaly = currentScore < threshold;
        double deviation = Math.abs(currentScore - meanScore);
        String message = buildMessage(isAnomaly, currentScore, threshold);

        return new AnomalyResult(isAnomaly, currentScore, threshold, ALGORITHM_NAME, message, meanScore, deviation);
    }

    private static double[] toDoubleArray(List<Double> historicalData, double currentValue) {
        int size = historicalData.size();
        double[] result = new double[size + 1];
        for (int i = 0; i < size; i++) {
            result[i] = historicalData.get(i);
        }
        result[size] = currentValue;
        return result;
    }

    private static void buildIsolationForest(double[] allData, double[] anomalyScores) {
        int totalSize = allData.length;
        int sampleSize = Math.min(SUBSAMPLE_SIZE, totalSize);

        for (int t = 0; t < NUM_TREES; t++) {
            double[] sample = bootstrapSample(allData, sampleSize);
            double minSample = findMin(sample);
            double maxSample = findMax(sample);

            for (int i = 0; i < totalSize; i++) {
                anomalyScores[i] += calculatePathLength(allData[i], minSample, maxSample);
            }
        }
    }

    private static double[] bootstrapSample(double[] data, int size) {
        Random random = ThreadLocalRandom.current();
        int dataSize = data.length;
        double[] sample = new double[size];
        for (int i = 0; i < size; i++) {
            sample[i] = data[random.nextInt(dataSize)];
        }
        return sample;
    }

    private static double findMin(double[] data) {
        double min = Double.POSITIVE_INFINITY;
        for (double value : data) {
            if (value < min) {
                min = value;
            }
        }
        return min;
    }

    private static double findMax(double[] data) {
        double max = Double.NEGATIVE_INFINITY;
        for (double value : data) {
            if (value > max) {
                max = value;
            }
        }
        return max;
    }

    private static double calculatePathLength(double value, double min, double max) {
        int depth = 0;
        double currentMin = min;
        double currentMax = max;

        while (currentMax - currentMin >= MIN_SPLIT_RANGE && depth < MAX_TREE_DEPTH) {
            double split = (currentMin + currentMax) * 0.5;
            if (value < split) {
                currentMax = split;
            } else {
                currentMin = split;
            }
            depth++;
        }
        return depth;
    }

    private static void normalizeScores(double[] anomalyScores) {
        for (int i = 0; i < anomalyScores.length; i++) {
            anomalyScores[i] /= NUM_TREES;
        }
    }

    private static double calculateThreshold(double[] scores, double sensitivity) {
        double[] sortedScores = Arrays.copyOf(scores, scores.length);
        Arrays.sort(sortedScores);
        int percentileIndex = (int) (sortedScores.length * (1 - sensitivity / 20));
        int safeIndex = Math.max(0, Math.min(percentileIndex, sortedScores.length - 1));
        return sortedScores[safeIndex];
    }

    private static double calculateMean(double[] scores) {
        double sum = 0.0;
        for (double score : scores) {
            sum += score;
        }
        return scores.length > 0 ? sum / scores.length : 0.0;
    }

    private static String buildMessage(boolean isAnomaly, double currentScore, double threshold) {
        if (isAnomaly) {
            return String.format("Anomaly detected: isolation score=%.4f, threshold=%.4f", currentScore, threshold);
        }
        return String.format("Normal: isolation score=%.4f, threshold=%.4f", currentScore, threshold);
    }

    private static AnomalyResult createInsufficientDataResult() {
        return new AnomalyResult(false, 0, 0, ALGORITHM_NAME, "Insufficient historical data", 0, 0);
    }
}
