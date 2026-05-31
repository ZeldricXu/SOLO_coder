package com.scheduler.tracing.sampling.impl;

import com.scheduler.persistence.entity.TraceSpan;
import com.scheduler.persistence.mapper.TraceSpanMapper;
import com.scheduler.tracing.sampling.SamplingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class TailSampler implements SamplingStrategy {

    private final TraceSpanMapper spanMapper;
    private final Map<String, AtomicInteger> traceSpanCounts = new ConcurrentHashMap<>();
    private static final int TRACE_COMPLETE_THRESHOLD = 5;
    private static final int MAX_SPANS_PER_TRACE = 100;

    @Override
    public String getName() {
        return "TAIL";
    }

    @Override
    public boolean shouldSample(TraceSpan span) {
        if (span.getTraceId() == null) {
            return false;
        }

        AtomicInteger count = traceSpanCounts.computeIfAbsent(span.getTraceId(), k -> new AtomicInteger(0));
        int spanCount = count.incrementAndGet();

        if (spanCount >= TRACE_COMPLETE_THRESHOLD) {
            return shouldSampleTrace(span.getTraceId());
        }

        if (spanCount > MAX_SPANS_PER_TRACE) {
            traceSpanCounts.remove(span.getTraceId());
            return false;
        }

        return true;
    }

    private boolean shouldSampleTrace(String traceId) {
        List<TraceSpan> spans = spanMapper.findByTraceId(traceId);
        if (spans.isEmpty()) {
            return false;
        }

        boolean hasError = spans.stream()
                .anyMatch(s -> "ERROR".equalsIgnoreCase(s.getStatus()));
        if (hasError) {
            log.debug("Tail sampling trace {} due to error", traceId);
            return true;
        }

        boolean hasSlowSpan = spans.stream()
                .filter(s -> s.getDurationMicros() != null)
                .anyMatch(s -> s.getDurationMicros() > 5000000);
        if (hasSlowSpan) {
            log.debug("Tail sampling trace {} due to slow span", traceId);
            return true;
        }

        return false;
    }

    @Override
    public void updateConfig(Object config) {
    }

    public void cleanupOldTraces() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        traceSpanCounts.keySet().removeIf(traceId -> {
            List<TraceSpan> spans = spanMapper.findByTraceId(traceId);
            return spans.isEmpty() || spans.get(0).getStartTime().isBefore(cutoff);
        });
    }
}
