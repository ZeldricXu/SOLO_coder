package com.datastandard.modules.slo.calculator;

import com.datastandard.modules.slo.dto.SliCalculationRequest;
import com.datastandard.modules.slo.entity.SloDefinition;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ThroughputSliCalculator implements SliTypeCalculator {

    @Override
    public double calculate(SliCalculationRequest request, SloDefinition slo) {
        var dataPoints = request.getDataPoints();
        if (dataPoints == null || dataPoints.isEmpty()) {
            return 0.0;
        }

        Duration windowDuration = Duration.between(request.getStartTime(), request.getEndTime());
        double totalValue = dataPoints.stream()
                .mapToDouble(dp -> dp.getValue() != null ? dp.getValue() : 0.0)
                .sum();

        return totalValue / windowDuration.getSeconds();
    }

    @Override
    public String getType() {
        return "THROUGHPUT";
    }
}
