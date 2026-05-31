package com.scheduler.anomaly.detection;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyResult {
    private boolean isAnomaly;
    private String metricName;
    private double currentValue;
    private double expectedValue;
    private double deviation;
    private double deviationPercent;
    private String severity;
    private String algorithm;
    private Instant timestamp;
    private Map<String, Object> details;
    private String description;

    public static AnomalyResult normal(String metricName, double value, String algorithm) {
        return AnomalyResult.builder()
                .isAnomaly(false)
                .metricName(metricName)
                .currentValue(value)
                .algorithm(algorithm)
                .timestamp(Instant.now())
                .build();
    }

    public AnomalyResult setDescription(String description) {
        this.description = description;
        return this;
    }

    public static AnomalyResult anomaly(String metricName, double current, double expected,
                                        String severity, String algorithm, String description) {
        double deviation = Math.abs(current - expected);
        double deviationPercent = expected > 0 ? (deviation / expected) * 100 : 0;
        return AnomalyResult.builder()
                .isAnomaly(true)
                .metricName(metricName)
                .currentValue(current)
                .expectedValue(expected)
                .deviation(deviation)
                .deviationPercent(deviationPercent)
                .severity(severity)
                .algorithm(algorithm)
                .timestamp(Instant.now())
                .description(description)
                .build();
    }
}
