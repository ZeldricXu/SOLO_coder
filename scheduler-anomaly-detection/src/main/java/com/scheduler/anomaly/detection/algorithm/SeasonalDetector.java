package com.scheduler.anomaly.detection.algorithm;

import com.scheduler.anomaly.detection.AnomalyDetector;
import com.scheduler.anomaly.detection.AnomalyResult;
import com.scheduler.persistence.entity.MetricsSnapshot;
import org.springframework.stereotype.Component;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
public class SeasonalDetector implements AnomalyDetector {

    private static final int SEASONAL_WINDOW_HOURS = 24;

    @Override
    public String getAlgorithmName() {
        return "SEASONAL";
    }

    @Override
    public AnomalyResult detect(List<MetricsSnapshot> historicalData, MetricsSnapshot currentData) {
        if (historicalData.isEmpty()) {
            return AnomalyResult.normal("no_history", 0, getAlgorithmName());
        }

        Instant currentTime = currentData.getTimestamp();
        DayOfWeek currentDay = currentTime.atZone(ZoneId.systemDefault()).getDayOfWeek();
        int currentHour = currentTime.atZone(ZoneId.systemDefault()).getHour();

        List<Double> seasonalValues = new ArrayList<>();
        for (MetricsSnapshot snapshot : historicalData) {
            Instant time = snapshot.getTimestamp();
            DayOfWeek day = time.atZone(ZoneId.systemDefault()).getDayOfWeek();
            int hour = time.atZone(ZoneId.systemDefault()).getHour();
            long hoursDiff = Math.abs(ChronoUnit.HOURS.between(time, currentTime));

            if (day == currentDay && Math.abs(hour - currentHour) <= 1 && hoursDiff >= 24) {
                Object throughput = snapshot.getMetrics().get("throughput");
                if (throughput instanceof Number) {
                    seasonalValues.add(((Number) throughput).doubleValue());
                }
            }
        }

        if (seasonalValues.size() < 3) {
            return AnomalyResult.normal("insufficient_seasonal_data", 0, getAlgorithmName());
        }

        double avg = seasonalValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double currentThroughput = currentData.getMetrics().getOrDefault("throughput", 0) instanceof Number
                ? ((Number) currentData.getMetrics().get("throughput")).doubleValue() : 0;

        double deviationPercent = avg > 0 ? Math.abs(currentThroughput - avg) / avg * 100 : 0;

        if (deviationPercent > 50) {
            String severity = deviationPercent > 80 ? "CRITICAL" : "WARNING";
            String description = String.format("Throughput %.0f deviates %.0f%% from seasonal average %.0f",
                    currentThroughput, deviationPercent, avg);
            return AnomalyResult.anomaly("throughput", currentThroughput, avg, severity, getAlgorithmName(), description);
        }

        return AnomalyResult.normal("throughput", currentThroughput, getAlgorithmName());
    }

    @Override
    public boolean supports(String metricType) {
        return "throughput".equals(metricType) || "all".equals(metricType);
    }
}
