package com.scheduler.log.pipeline.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineStatusMonitor {

    private final PipelineMetrics metrics;

    private final AtomicLong lastProcessedCount = new AtomicLong(0);
    private final AtomicLong lastErrorCount = new AtomicLong(0);
    private volatile Instant lastReportTime = Instant.now();

    private volatile double currentThroughput = 0;
    private volatile double currentErrorRate = 0;
    private volatile boolean healthy = true;

    @Scheduled(fixedDelay = 10000)
    public void reportStatus() {
        long currentProcessed = metrics.getTotalProcessed().get();
        long currentErrors = metrics.getTotalErrors().get();
        Instant now = Instant.now();
        long elapsedSeconds = Duration.between(lastReportTime, now).getSeconds();

        if (elapsedSeconds > 0) {
            long processedDelta = currentProcessed - lastProcessedCount.get();
            long errorDelta = currentErrors - lastErrorCount.get();

            currentThroughput = (double) processedDelta / elapsedSeconds;
            currentErrorRate = processedDelta > 0 ? (double) errorDelta / processedDelta : 0;

            healthy = currentErrorRate < 0.1;

            if (processedDelta > 0) {
                log.debug("Pipeline status: throughput={}/s, errorRate={:.2f}%, healthy={}",
                        String.format("%.1f", currentThroughput),
                        currentErrorRate * 100,
                        healthy);
            }
        }

        lastProcessedCount.set(currentProcessed);
        lastErrorCount.set(currentErrors);
        lastReportTime = now;
    }

    public Mono<Map<String, Object>> getCurrentStatus() {
        return Mono.fromCallable(() -> Map.of(
                "healthy", healthy,
                "throughputPerSecond", String.format("%.2f", currentThroughput),
                "errorRate", String.format("%.4f", currentErrorRate),
                "totalProcessed", metrics.getTotalProcessed().get(),
                "totalErrors", metrics.getTotalErrors().get(),
                "totalFiltered", metrics.getTotalFiltered().get(),
                "totalRouted", metrics.getTotalRouted().get(),
                "lastReportTime", lastReportTime.toString()
        ));
    }

    public boolean isHealthy() {
        return healthy;
    }

    public double getCurrentThroughput() {
        return currentThroughput;
    }

    public double getCurrentErrorRate() {
        return currentErrorRate;
    }
}
