package com.datastandard.modules.slo.calculator;

import com.datastandard.modules.slo.dto.SliCalculationRequest;
import com.datastandard.modules.slo.entity.SloDefinition;
import org.springframework.stereotype.Component;

@Component
public class AvailabilitySliCalculator implements SliTypeCalculator {

    @Override
    public double calculate(SliCalculationRequest request, SloDefinition slo) {
        var dataPoints = request.getDataPoints();
        if (dataPoints == null || dataPoints.isEmpty()) {
            return 1.0;
        }

        long totalEvents = dataPoints.size();
        long goodEvents = dataPoints.stream()
                .filter(SliCalculationRequest.DataPoint::isSuccess)
                .count();

        return totalEvents > 0 ? (double) goodEvents / totalEvents : 1.0;
    }

    @Override
    public String getType() {
        return "AVAILABILITY";
    }
}
