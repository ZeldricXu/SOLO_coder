package com.logmanager.service.slo;

import com.logmanager.domain.model.SLOConfig;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface SLICalculator {
    Mono<Double> calculate(SLOConfig slo);

    default String getType() {
        return "default";
    }
}
