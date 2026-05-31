package com.monitoring.trace.sampling;

import com.monitoring.trace.model.TraceSpan;

import java.util.List;

public interface SamplingStrategy {

    String getName();

    boolean shouldSample(TraceSpan span, SamplingContext context);

    default boolean shouldSampleTail(String traceId, List<TraceSpan> spans, SamplingContext context) {
        return false;
    }

    record SamplingContext(
            double samplingRate,
            double errorThreshold,
            long latencyThresholdMs,
            boolean tailSamplingEnabled
    ) {}
}
