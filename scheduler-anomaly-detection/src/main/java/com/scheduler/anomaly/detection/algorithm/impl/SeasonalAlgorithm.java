package com.scheduler.anomaly.detection.algorithm.impl;

import com.scheduler.anomaly.detection.AnomalyResult;
import com.scheduler.anomaly.detection.algorithm.AnomalyDetectionAlgorithm;
import com.scheduler.anomaly.detection.model.MetricSeries;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SeasonalAlgorithm implements AnomalyDetectionAlgorithm {

    private static final double SEASONAL_DEVIATION_THRESHOLD = 0.3;
    private static final int SEASONAL_PERIOD = 24;

    @Override
    public String getName() {
        return "SEASONAL";
    }

    @Override
    public AnomalyResult detect(MetricSeries history, double currentValue) {
        String metricName = history.getMetricName();
        List<Double> values = history.getValues();

        if (values.size() < SEASONAL_PERIOD * 2) {
            return AnomalyResult.normal(metricName, currentValue, getName())
                    .setDescription("Insufficient data for seasonal analysis");
        }

        double seasonalBaseline = calculateSeasonalBaseline(values);
        double deviation = Math.abs(currentValue - seasonalBaseline) / seasonalBaseline;

        if (deviation > SEASONAL_DEVIATION_THRESHOLD) {
            String severity = deviation > 0.5 ? "CRITICAL" : "WARNING";
            String description = String.format(
                    "Value %.2f deviates %.1f%% from seasonal baseline %.2f (threshold: %.0f%%)",
                    currentValue, deviation * 100, seasonalBaseline, SEASONAL_DEVIATION_THRESHOLD * 100);
            return AnomalyResult.anomaly(metricName, currentValue, seasonalBaseline, severity, getName(), description);
        }

        return AnomalyResult.normal(metricName, currentValue, getName());
    }

    private double calculateSeasonalBaseline(List<Double> values) {
        List<Double> samePeriodValues = new ArrayList<>();
        int totalSize = values.size();

        for (int i = totalSize - SEASONAL_PERIOD; i >= 0; i -= SEASONAL_PERIOD) {
            samePeriodValues.add(values.get(i));
        }

        return samePeriodValues.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);
    }
}
