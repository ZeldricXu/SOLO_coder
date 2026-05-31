package com.monitoring.trace.sampling.impl;

import com.monitoring.trace.model.TraceSpan;
import com.monitoring.trace.sampling.SamplingStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ErrorSamplingStrategy implements SamplingStrategy {

    private static final String STRATEGY_NAME = "error";
    private static final int ERROR_HTTP_STATUS_THRESHOLD = 500;

    @Override
    public String getName() {
        return STRATEGY_NAME;
    }

    @Override
    public boolean shouldSample(TraceSpan span, SamplingContext context) {
        return isErrorSpan(span);
    }

    @Override
    public boolean shouldSampleTail(String traceId, List<TraceSpan> spans, SamplingContext context) {
        if (!context.tailSamplingEnabled() || spans.isEmpty()) {
            return false;
        }

        long errorCount = spans.stream()
                .filter(ErrorSamplingStrategy::isErrorSpan)
                .count();

        double errorRate = (double) errorCount / spans.size();
        return errorRate >= context.errorThreshold();
    }

    private static boolean isErrorSpan(TraceSpan span) {
        return Boolean.TRUE.equals(span.getError())
                || (span.getHttpStatus() != null && span.getHttpStatus() >= ERROR_HTTP_STATUS_THRESHOLD);
    }
}
