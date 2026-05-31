package com.scheduler.log.pipeline.monitor;

import io.micrometer.core.instrument.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@Getter
public class PipelineMetrics {

    private final MeterRegistry meterRegistry;

    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final AtomicLong totalFiltered = new AtomicLong(0);
    private final AtomicLong totalRouted = new AtomicLong(0);

    private final ConcurrentMap<String, AtomicLong> stageProcessed = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> stageErrors = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> stageTimers = new ConcurrentHashMap<>();

    private Timer totalProcessingTimer;
    private DistributionSummary logSizeSummary;
    private Counter processedCounter;
    private Counter errorCounter;
    private Counter filteredCounter;
    private Counter routedCounter;

    public PipelineMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        totalProcessingTimer = Timer.builder("log.pipeline.total.processing.time")
                .description("Total time spent processing log entries")
                .register(meterRegistry);

        logSizeSummary = DistributionSummary.builder("log.pipeline.entry.size.bytes")
                .description("Size distribution of log entries")
                .baseUnit("bytes")
                .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                .register(meterRegistry);

        processedCounter = Counter.builder("log.pipeline.processed.total")
                .description("Total number of processed log entries")
                .register(meterRegistry);

        errorCounter = Counter.builder("log.pipeline.errors.total")
                .description("Total number of processing errors")
                .register(meterRegistry);

        filteredCounter = Counter.builder("log.pipeline.filtered.total")
                .description("Total number of filtered log entries")
                .register(meterRegistry);

        routedCounter = Counter.builder("log.pipeline.routed.total")
                .description("Total number of routed log entries")
                .register(meterRegistry);

        log.info("Pipeline metrics initialized");
    }

    public void recordProcessing(long durationNanos, boolean success) {
        totalProcessingTimer.record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        if (success) {
            totalProcessed.incrementAndGet();
            processedCounter.increment();
        } else {
            totalErrors.incrementAndGet();
            errorCounter.increment();
        }
    }

    public void recordStageProcessing(String stageName, long durationNanos, boolean success) {
        stageProcessed.computeIfAbsent(stageName, k -> new AtomicLong(0)).incrementAndGet();
        Timer timer = stageTimers.computeIfAbsent(stageName, this::createStageTimer);
        timer.record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);

        if (!success) {
            stageErrors.computeIfAbsent(stageName, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    private Timer createStageTimer(String stageName) {
        return Timer.builder("log.pipeline.stage.processing.time")
                .description("Processing time per pipeline stage")
                .tag("stage", stageName)
                .register(meterRegistry);
    }

    public void recordLogSize(long sizeBytes) {
        logSizeSummary.record(sizeBytes);
    }

    public void recordFiltered() {
        totalFiltered.incrementAndGet();
        filteredCounter.increment();
    }

    public void recordRouted() {
        totalRouted.incrementAndGet();
        routedCounter.increment();
    }

    public java.util.Map<String, Object> getStats() {
        return java.util.Map.of(
                "totalProcessed", totalProcessed.get(),
                "totalErrors", totalErrors.get(),
                "totalFiltered", totalFiltered.get(),
                "totalRouted", totalRouted.get(),
                "stageProcessed", new java.util.HashMap<>(stageProcessed),
                "stageErrors", new java.util.HashMap<>(stageErrors)
        );
    }
}
