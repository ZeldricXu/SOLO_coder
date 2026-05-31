package com.monitoring.trace.sampling.impl;

import com.monitoring.trace.model.TraceSpan;
import com.monitoring.trace.sampling.SamplingStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class LatencySamplingStrategy implements SamplingStrategy {

    private static final String STRATEGY_NAME = "latency";

    @Override
    public String getName() {
        return STRATEGY_NAME;
    }

    @Override
    public boolean shouldSample(TraceSpan span, SamplingContext context) {
        if (span.getDurationNanos() == null) {
            return false;
        }
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(span.getDurationNanos());
        return latencyMs >= context.latencyThresholdMs();
    }

    @Override
    public boolean shouldSampleTail(String traceId, List<TraceSpan> spans, SamplingContext context) {
        if (!context.tailSamplingEnabled() || spans.isEmpty()) {
            return false;
        }

        long totalDurationNanos = spans.stream()
                .mapToLong(span -> span.getDurationNanos() != null ? span.getDurationNanos() : 0L)
                .sum();

        long totalDurationMs = TimeUnit.NANOSECONDS.toMillis(totalDurationNanos);
        return totalDurationMs >= context.latencyThresholdMs();
    }
}
