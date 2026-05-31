package com.scheduler.tracing.sampling.impl;

import com.scheduler.persistence.entity.TraceSpan;
import com.scheduler.tracing.sampling.SamplingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
public class ProbabilisticSampler implements SamplingStrategy {

    @Value("${tracing.sampling.probability:0.1}")
    private double samplingProbability;

    @Override
    public String getName() {
        return "PROBABILISTIC";
    }

    @Override
    public boolean shouldSample(TraceSpan span) {
        double random = ThreadLocalRandom.current().nextDouble();
        return random < samplingProbability;
    }

    @Override
    public void updateConfig(Object config) {
        if (config instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = (Map<String, Object>) config;
            if (cfg.containsKey("probability")) {
                this.samplingProbability = ((Number) cfg.get("probability")).doubleValue();
                log.info("Updated probabilistic sampling probability to: {}", samplingProbability);
            }
        }
    }
}
