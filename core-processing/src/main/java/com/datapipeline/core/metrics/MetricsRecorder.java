package com.datapipeline.core.metrics;

import com.datapipeline.common.model.StatisticsSnapshot;
import com.datapipeline.common.tracing.TraceContext;
import com.datapipeline.common.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
public class MetricsRecorder {

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder successRequests = new LongAdder();
    private final LongAdder failedRequests = new LongAdder();
    private final LongAdder timeoutRequests = new LongAdder();
    private final AtomicLong totalLatencyNanos = new AtomicLong(0);
    private final AtomicLong maxLatencyNanos = new AtomicLong(0);

    private final Map<String, LongAdder> customCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> customGauges = new ConcurrentHashMap<>();

    public void recordRequest(boolean success, long latencyNanos, String errorType) {
        totalRequests.increment();
        if (success) {
            successRequests.increment();
        } else {
            if ("TIMEOUT".equals(errorType)) {
                timeoutRequests.increment();
            } else {
                failedRequests.increment();
            }
        }
        totalLatencyNanos.addAndGet(latencyNanos);
        maxLatencyNanos.updateAndGet(current -> Math.max(current, latencyNanos));
    }

    public void recordTraceContext(TraceContext ctx) {
        if (ctx == null) {
            return;
        }
        long latencyNanos = ctx.durationMillis() * 1_000_000L;
        String errorType = ctx.isSuccess() ? null : (ctx.getErrorCode() != null ? ctx.getErrorCode() : "UNKNOWN");
        recordRequest(ctx.isSuccess(), latencyNanos, errorType);
    }

    public void incrementCounter(String name) {
        customCounters.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    public void incrementCounter(String name, long delta) {
        customCounters.computeIfAbsent(name, k -> new LongAdder()).add(delta);
    }

    public void setGauge(String name, long value) {
        customGauges.computeIfAbsent(name, k -> new AtomicLong(0)).set(value);
    }

    public long getTotalRequests() {
        return totalRequests.sum();
    }

    public long getSuccessRequests() {
        return successRequests.sum();
    }

    public long getFailedRequests() {
        return failedRequests.sum();
    }

    public long getTimeoutRequests() {
        return timeoutRequests.sum();
    }

    public double getAverageLatencyMs() {
        long total = totalRequests.sum();
        if (total == 0) {
            return 0.0;
        }
        return (totalLatencyNanos.get() / 1_000_000.0) / total;
    }

    public double getMaxLatencyMs() {
        return maxLatencyNanos.get() / 1_000_000.0;
    }

    public double getErrorRate() {
        long total = totalRequests.sum();
        if (total == 0) {
            return 0.0;
        }
        return (failedRequests.sum() + timeoutRequests.sum()) / (double) total;
    }

    public StatisticsSnapshot snapshot(Map<String, String> dimensions) {
        StatisticsSnapshot snapshot = StatisticsSnapshot.builder()
                .snapshotId(IdGenerator.generate("snap"))
                .timestamp(Instant.now())
                .dimensions(new HashMap<>(dimensions))
                .build();
        snapshot.metric("throughput", getTotalRequests());
        snapshot.metric("success_count", getSuccessRequests());
        snapshot.metric("error_count", getFailedRequests());
        snapshot.metric("timeout_count", getTimeoutRequests());
        snapshot.metric("latency_avg_ms", getAverageLatencyMs());
        snapshot.metric("latency_max_ms", getMaxLatencyMs());
        snapshot.metric("error_rate", getErrorRate());
        return snapshot;
    }

    public StatisticsSnapshot snapshot() {
        return snapshot(java.util.Collections.emptyMap());
    }

}
