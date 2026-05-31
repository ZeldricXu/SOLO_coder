package com.datamasker.domain.mpc.monitor;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class MpcExecutionTracer {

    private final Map<String, TraceSpan> spans = new ConcurrentHashMap<>();
    private final Map<String, List<String>> sessionSpans = new ConcurrentHashMap<>();

    public String startSpan(String operation, String sessionId) {
        String spanId = UUID.randomUUID().toString().replace("-", "");
        TraceSpan span = new TraceSpan();
        span.setSpanId(spanId);
        span.setOperation(operation);
        span.setSessionId(sessionId);
        span.setStartTime(LocalDateTime.now());
        spans.put(spanId, span);
        sessionSpans.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(spanId);
        return spanId;
    }

    public void endSpan(String spanId, boolean success) {
        TraceSpan span = spans.get(spanId);
        if (span != null) {
            span.setDurationMs(ChronoUnit.MILLIS.between(span.getStartTime(), LocalDateTime.now()));
            span.setSuccess(success);
        }
    }

    public List<TraceSpan> getTrace(String sessionId) {
        List<String> spanIds = sessionSpans.getOrDefault(sessionId, new ArrayList<>());
        return spanIds.stream()
                .map(spans::get)
                .filter(span -> span != null && span.getDurationMs() != null)
                .collect(Collectors.toList());
    }

    public List<TraceSpan> getSlowTraces(int thresholdMs) {
        return spans.values().stream()
                .filter(span -> span.getDurationMs() != null && span.getDurationMs() > thresholdMs)
                .collect(Collectors.toList());
    }

    @Data
    public static class TraceSpan {
        private String spanId;
        private String operation;
        private String sessionId;
        private LocalDateTime startTime;
        private Long durationMs;
        private boolean success;
    }
}
