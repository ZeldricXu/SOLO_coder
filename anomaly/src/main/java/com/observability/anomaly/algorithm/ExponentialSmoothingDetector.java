package com.observability.anomaly.algorithm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ExponentialSmoothingDetector implements AnomalyDetector {

    @Override
    public String getName() {
        return "exponential_smoothing";
    }

    @Override
    public AnomalyResult detect(List<Double> historicalData, double currentValue, Map<String, Object> params) {
        if (historicalData == null || historicalData.size() < 5) {
            return AnomalyResult.normal(getName(), currentValue, currentValue);
        }

        double alpha = params != null && params.containsKey("alpha") ?
                ((Number) params.get("alpha")).doubleValue() : 0.3;
        double thresholdMultiplier = params != null && params.containsKey("threshold") ?
                ((Number) params.get("threshold")).doubleValue() : 2.0;

        double smoothed = calculateEMA(historicalData, alpha);
        double stdDev = calculateErrorStdDev(historicalData, alpha);
        double threshold = stdDev * thresholdMultiplier;
        double deviation = Math.abs(currentValue - smoothed);

        if (deviation > threshold) {
            String severity = deviation > threshold * 2 ? "critical" :
                    deviation > threshold * 1.5 ? "warning" : "info";
            return AnomalyResult.anomaly(getName(), currentValue, smoothed, threshold, deviation, severity);
        }

        return AnomalyResult.normal(getName(), currentValue, smoothed);
    }

    private double calculateEMA(List<Double> data, double alpha) {
        double ema = data.get(0);
        for (int i = 1; i < data.size(); i++) {
            ema = alpha * data.get(i) + (1 - alpha) * ema;
        }
        return ema;
    }

    private double calculateErrorStdDev(List<Double> data, double alpha) {
        double ema = data.get(0);
        double sumSquaredError = 0;
        for (int i = 1; i < data.size(); i++) {
            double error = data.get(i) - ema;
            sumSquaredError += error * error;
            ema = alpha * data.get(i) + (1 - alpha) * ema;
        }
        return Math.sqrt(sumSquaredError / (data.size() - 1));
    }
}
