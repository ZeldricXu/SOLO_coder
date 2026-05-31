package com.scheduler.tracing.sampling;

import com.scheduler.persistence.entity.TraceSpan;

public interface SamplingStrategy {
    String getName();
    boolean shouldSample(TraceSpan span);
    void updateConfig(Object config);
}
