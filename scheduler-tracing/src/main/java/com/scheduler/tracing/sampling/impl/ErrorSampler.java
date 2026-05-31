package com.scheduler.tracing.sampling.impl;

import com.scheduler.persistence.entity.TraceSpan;
import com.scheduler.tracing.sampling.SamplingStrategy;
import org.springframework.stereotype.Component;

@Component
public class ErrorSampler implements SamplingStrategy {

    @Override
    public String getName() {
        return "ERROR";
    }

    @Override
    public boolean shouldSample(TraceSpan span) {
        if ("ERROR".equalsIgnoreCase(span.getStatus())) {
            return true;
        }
        if (span.getTags() != null && span.getTags().containsKey("error")) {
            Object error = span.getTags().get("error");
            return Boolean.TRUE.equals(error) || "true".equals(String.valueOf(error));
        }
        return false;
    }

    @Override
    public void updateConfig(Object config) {
    }
}
