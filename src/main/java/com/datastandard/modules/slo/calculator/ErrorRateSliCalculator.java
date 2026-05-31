package com.datastandard.modules.slo.calculator;

import com.datastandard.modules.slo.dto.SliCalculationRequest;
import com.datastandard.modules.slo.entity.SloDefinition;
import org.springframework.stereotype.Component;

@Component
public class ErrorRateSliCalculator implements SliTypeCalculator {

    @Override
    public double calculate(SliCalculationRequest request, SloDefinition slo) {
        var dataPoints = request.getDataPoints();
        if (dataPoints == null || dataPoints.isEmpty()) {
            return 0.0;
        }

        long totalEvents = dataPoints.size();
        long errorEvents = dataPoints.stream()
                .filter(dp -> !dp.isSuccess())
                .count();

        return (double) errorEvents / totalEvents;
    }

    @Override
    public String getType() {
        return "ERROR_RATE";
    }
}
