package com.scheduler.log.pipeline.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class StageLatencyTracker {

    private final PipelineMetrics metrics;

    private final Map<String, AtomicLong> totalLatencyNanos = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> invocationCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> maxLatencyNanos = new ConcurrentHashMap<>();

    public LatencyContext startStage(String stageName) {
        return new LatencyContext(stageName, System.nanoTime());
    }

    public void endStage(LatencyContext context, boolean success) {
        long duration = System.nanoTime() - context.startTimeNanos;
        String stageName = context.stageName;

        metrics.recordStageProcessing(stageName, duration, success);

        totalLatencyNanos.computeIfAbsent(stageName, k -> new AtomicLong(0))
                .addAndGet(duration);
        invocationCount.computeIfAbsent(stageName, k -> new AtomicLong(0))
                .incrementAndGet();
        maxLatencyNanos.compute(stageName, (k, current) -> {
            if (current == null || duration > current.get()) {
                return new AtomicLong(duration);
            }
            return current;
        });
    }

    public Map<String, Object> getStageLatencyStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();

        for (String stageName : invocationCount.keySet()) {
            long count = invocationCount.getOrDefault(stageName, new AtomicLong(0)).get();
            long totalLatency = totalLatencyNanos.getOrDefault(stageName, new AtomicLong(0)).get();
            long maxLatency = maxLatencyNanos.getOrDefault(stageName, new AtomicLong(0)).get();

            double avgLatencyMs = count > 0 ? (totalLatency / (double) count) / 1_000_000.0 : 0;
            double maxLatencyMs = maxLatency / 1_000_000.0;

            stats.put(stageName, Map.of(
                    "invocationCount", count,
                    "avgLatencyMs", String.format("%.3f", avgLatencyMs),
                    "maxLatencyMs", String.format("%.3f", maxLatencyMs),
                    "totalLatencyMs", String.format("%.3f", totalLatency / 1_000_000.0)
            ));
        }

        return stats;
    }

    public void resetStats() {
        totalLatencyNanos.clear();
        invocationCount.clear();
        maxLatencyNanos.clear();
        log.info("Stage latency statistics reset");
    }

    public static class LatencyContext {
        private final String stageName;
        private final long startTimeNanos;

        LatencyContext(String stageName, long startTimeNanos) {
            this.stageName = stageName;
            this.startTimeNanos = startTimeNanos;
        }

        public String getStageName() {
            return stageName;
        }

        public long getStartTimeNanos() {
            return startTimeNanos;
        }
    }
}
