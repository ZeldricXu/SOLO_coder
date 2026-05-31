package com.observability.anomaly.algorithm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ThreeSigmaDetector implements AnomalyDetector {

    @Override
    public String getName() {
        return "3sigma";
    }

    @Override
    public AnomalyResult detect(List<Double> historicalData, double currentValue, Map<String, Object> params) {
        if (historicalData == null || historicalData.size() < 3) {
            return AnomalyResult.normal(getName(), currentValue, currentValue);
        }

        double mean = calculateMean(historicalData);
        double stdDev = calculateStdDev(historicalData, mean);

        double sigma = params != null && params.containsKey("sigma") ?
                ((Number) params.get("sigma")).doubleValue() : 3.0;
        double threshold = stdDev * sigma;
        double deviation = Math.abs(currentValue - mean);

        if (deviation > threshold) {
            String severity = deviation > threshold * 2 ? "critical" :
                    deviation > threshold * 1.5 ? "warning" : "info";
            return AnomalyResult.anomaly(getName(), currentValue, mean, threshold, deviation, severity);
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
