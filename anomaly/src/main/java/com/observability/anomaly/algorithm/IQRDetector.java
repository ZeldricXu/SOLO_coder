package com.observability.anomaly.algorithm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class IQRDetector implements AnomalyDetector {

    @Override
    public String getName() {
        return "iqr";
    }

    @Override
    public AnomalyResult detect(List<Double> historicalData, double currentValue, Map<String, Object> params) {
        if (historicalData == null || historicalData.size() < 4) {
            return AnomalyResult.normal(getName(), currentValue, currentValue);
        }

        List<Double> sorted = historicalData.stream().sorted().toList();
        double q1 = calculateQuantile(sorted, 0.25);
        double q3 = calculateQuantile(sorted, 0.75);
        double iqr = q3 - q1;

        double k = params != null && params.containsKey("k") ?
                ((Number) params.get("k")).doubleValue() : 1.5;
        double lowerBound = q1 - k * iqr;
        double upperBound = q3 + k * iqr;
        double median = calculateQuantile(sorted, 0.5);

        if (currentValue < lowerBound || currentValue > upperBound) {
            double deviation = currentValue > upperBound ?
                    currentValue - upperBound : lowerBound - currentValue;
            String severity = deviation > iqr * 2 ? "critical" :
                    deviation > iqr ? "warning" : "info";
            return AnomalyResult.anomaly(getName(), currentValue, median,
                    currentValue > upperBound ? upperBound : lowerBound, deviation, severity);
        }

        return AnomalyResult.normal(getName(), currentValue, median);
    }

    private double calculateQuantile(List<Double> sortedData, double quantile) {
        int n = sortedData.size();
        double index = quantile * (n - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sortedData.get(lower);
        }
        double weight = index - lower;
        return sortedData.get(lower) * (1 - weight) + sortedData.get(upper) * weight;
    }
}
