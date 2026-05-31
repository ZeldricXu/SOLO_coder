package com.monitoring.trace.sampling.impl;

import com.monitoring.trace.model.TraceSpan;
import com.monitoring.trace.sampling.SamplingStrategy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class ProbabilisticSamplingStrategy implements SamplingStrategy {

    private static final String STRATEGY_NAME = "probabilistic";

    @Override
    public String getName() {
        return STRATEGY_NAME;
    }

    @Override
    public boolean shouldSample(TraceSpan span, SamplingContext context) {
        return ThreadLocalRandom.current().nextDouble() < context.samplingRate();
    }
}
