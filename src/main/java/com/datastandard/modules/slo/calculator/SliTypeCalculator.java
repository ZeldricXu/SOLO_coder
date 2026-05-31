package com.datastandard.modules.slo.calculator;

import com.datastandard.modules.slo.dto.SliCalculationRequest;
import com.datastandard.modules.slo.entity.SloDefinition;

public interface SliTypeCalculator {
    double calculate(SliCalculationRequest request, SloDefinition slo);
    String getType();
}
