package com.datastandard.modules.slo.budget;

import com.datastandard.modules.slo.entity.SliMetric;
import com.datastandard.modules.slo.entity.SloDefinition;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class BudgetCalculator {

    public double calculateAverageSli(List<SliMetric> sliMetrics) {
        if (sliMetrics == null || sliMetrics.isEmpty()) {
            return 1.0;
        }
        return sliMetrics.stream()
                .mapToDouble(SliMetric::getSliValue)
                .average()
                .orElse(1.0);
    }

    public double calculateConsumedBudget(SloDefinition slo, List<SliMetric> sliMetrics,
                                           Instant windowStart, Instant windowEnd) {
        if (sliMetrics == null || sliMetrics.isEmpty()) {
            return 0.0;
        }

        double targetValue = slo.getTargetValue();
        String targetDirection = slo.getTargetDirection();
        double totalBudget = 1.0 - targetValue;

        double consumed = sliMetrics.stream()
                .mapToDouble(metric -> calculateWeightedConsumption(metric, targetValue, targetDirection, windowStart, windowEnd))
                .sum();

        return Math.min(consumed, totalBudget);
    }

    private double calculateWeightedConsumption(SliMetric metric, double targetValue,
                                                 String targetDirection,
                                                 Instant windowStart, Instant windowEnd) {
        double sliValue = metric.getSliValue();
        long metricWindowSeconds = Duration.between(metric.getWindowStart(), metric.getWindowEnd()).getSeconds();
        long totalSeconds = Duration.between(windowStart, windowEnd).getSeconds();
        double weight = totalSeconds > 0 ? (double) metricWindowSeconds / totalSeconds : 1.0;

        if (isBelowTarget(sliValue, targetValue, targetDirection)) {
            return (targetValue - sliValue) * weight;
        } else if (isAboveTarget(sliValue, targetValue, targetDirection)) {
            return (sliValue - targetValue) * weight;
        }
        return 0;
    }

    private boolean isBelowTarget(double sliValue, double targetValue, String targetDirection) {
        return "GREATER_THAN".equalsIgnoreCase(targetDirection) ||
                "GREATER_THAN_OR_EQUAL".equalsIgnoreCase(targetDirection);
    }

    private boolean isAboveTarget(double sliValue, double targetValue, String targetDirection) {
        return !"GREATER_THAN".equalsIgnoreCase(targetDirection) &&
                !"GREATER_THAN_OR_EQUAL".equalsIgnoreCase(targetDirection);
    }

    public double calculateBurnRate(double consumedBudget, Duration windowDuration) {
        if (windowDuration.isZero() || windowDuration.isNegative()) {
            return 0;
        }
        long windowHours = windowDuration.toHours();
        if (windowHours == 0) {
            return consumedBudget * 24;
        }
        return (consumedBudget / windowHours) * 24 * 30;
    }

    public String determineBudgetStatus(double remainingBudgetPercent) {
        if (remainingBudgetPercent <= 0) {
            return "EXHAUSTED";
        } else if (remainingBudgetPercent < 10) {
            return "CRITICAL";
        } else if (remainingBudgetPercent < 30) {
            return "WARNING";
        } else if (remainingBudgetPercent < 70) {
            return "MODERATE";
        } else {
            return "HEALTHY";
        }
    }
}
