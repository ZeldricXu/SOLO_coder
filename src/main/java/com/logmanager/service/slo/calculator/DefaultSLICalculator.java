package com.logmanager.service.slo.calculator;

import com.logmanager.domain.model.SLOConfig;
import com.logmanager.service.slo.SLICalculator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DefaultSLICalculator implements SLICalculator {

    private final Map<String, Double> sliMetrics = new ConcurrentHashMap<>();

    @Override
    public Mono<Double> calculate(SLOConfig slo) {
        double sli = sliMetrics.getOrDefault(slo.getSloId(), 99.9);
        return Mono.just(sli);
    }

    public void updateSLI(String sloId, double value) {
        sliMetrics.put(sloId, value);
    }
}
