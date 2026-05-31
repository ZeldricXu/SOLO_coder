package com.datastandard.modules.slo.calculator;

import com.datastandard.modules.slo.dto.SliCalculationRequest;
import com.datastandard.modules.slo.entity.SloDefinition;
import org.springframework.stereotype.Component;

@Component
public class LatencySliCalculator implements SliTypeCalculator {

    @Override
    public double calculate(SliCalculationRequest request, SloDefinition slo) {
        var dataPoints = request.getDataPoints();
        if (dataPoints == null || dataPoints.isEmpty()) {
            return 0.0;
        }

        Double threshold = extractThreshold(slo);
        if (threshold != null) {
            double finalThreshold = threshold;
            long goodEvents = dataPoints.stream()
                    .filter(dp -> dp.getValue() != null && dp.getValue() <= finalThreshold)
                    .count();
            return (double) goodEvents / dataPoints.size();
        }

        return dataPoints.stream()
                .mapToDouble(dp -> dp.getValue() != null ? dp.getValue() : 0.0)
                .average()
                .orElse(0.0);
    }

    @Override
    public String getType() {
        return "LATENCY";
    }

    private Double extractThreshold(SloDefinition slo) {
        if (slo.getTargetDirection() != null && slo.getTargetValue() != null) {
            if ("LESS_THAN".equalsIgnoreCase(slo.getTargetDirection()) ||
                    "LESS_THAN_OR_EQUAL".equalsIgnoreCase(slo.getTargetDirection())) {
                return slo.getTargetValue();
            }
        }
        return null;
    }
}
