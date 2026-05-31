package com.observability.anomaly.algorithm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ZScoreDetector implements AnomalyDetector {

    @Override
    public String getName() {
        return "zscore";
    }

    @Override
    public AnomalyResult detect(List<Double> historicalData, double currentValue, Map<String, Object> params) {
        if (historicalData == null || historicalData.size() < 3) {
            return AnomalyResult.normal(getName(), currentValue, currentValue);
        }

        double mean = calculateMean(historicalData);
        double stdDev = calculateStdDev(historicalData, mean);

        if (stdDev == 0) {
            return AnomalyResult.normal(getName(), currentValue, mean);
        }

        double zScore = (currentValue - mean) / stdDev;
        double threshold = params != null && params.containsKey("threshold") ?
                ((Number) params.get("threshold")).doubleValue() : 2.5;

        if (Math.abs(zScore) > threshold) {
            String severity = Math.abs(zScore) > threshold * 2 ? "critical" :
                    Math.abs(zScore) > threshold * 1.5 ? "warning" : "info";
            AnomalyResult result = AnomalyResult.anomaly(
                    getName(), currentValue, mean, threshold, Math.abs(zScore), severity);
            if (result.getDetails() != null) {
                result.getDetails().put("zScore", zScore);
            }
            return result;
        }

        return AnomalyResult.normal(getName(), currentValue, mean);
    }

    private double calculateMean(List<Double> data) {
        return data.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double calculateStdDev(List<Double> data, double mean) {
        double sumSquaredDiff = data.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .sum();
        return Math.sqrt(sumSquaredDiff / data.size());
    }
}
