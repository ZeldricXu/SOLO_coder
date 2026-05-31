package com.scheduler.log.pipeline.service;

import com.scheduler.log.pipeline.model.LogEntry;
import com.scheduler.log.pipeline.monitor.PipelineMetrics;
import com.scheduler.log.pipeline.monitor.PipelineStatusMonitor;
import com.scheduler.log.pipeline.monitor.StageLatencyTracker;
import com.scheduler.log.pipeline.pipeline.Pipeline;
import com.scheduler.log.pipeline.pipeline.PipelineFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Service
public class LogPipelineService {

    private final PipelineFactory pipelineFactory;
    private final PipelineMetrics pipelineMetrics;
    private final PipelineStatusMonitor statusMonitor;
    private final StageLatencyTracker latencyTracker;
    private final Pipeline<LogEntry> defaultPipeline;
    private final Map<String, Consumer<LogEntry>> registeredRoutes = new ConcurrentHashMap<>();

    @Getter
    private final MeterRegistry meterRegistry;

    public LogPipelineService(PipelineFactory pipelineFactory,
                              PipelineMetrics pipelineMetrics,
                              PipelineStatusMonitor statusMonitor,
                              StageLatencyTracker latencyTracker,
                              MeterRegistry meterRegistry) {
        this.pipelineFactory = pipelineFactory;
        this.pipelineMetrics = pipelineMetrics;
        this.statusMonitor = statusMonitor;
        this.latencyTracker = latencyTracker;
        this.meterRegistry = meterRegistry;
        this.defaultPipeline = pipelineFactory.createLogPipeline("default");
    }

    public Mono<LogEntry> process(LogEntry entry) {
        long startTimeNanos = System.nanoTime();
        Timer.Sample sample = Timer.start(meterRegistry);

        return defaultPipeline.process(entry)
                .doOnNext(e -> {
                    long durationNanos = System.nanoTime() - startTimeNanos;
                    pipelineMetrics.recordProcessing(durationNanos, true);
                    sample.stop(meterRegistry.timer("log.pipeline.processing"));

                    if (e.getSizeBytes() > 0) {
                        pipelineMetrics.recordLogSize(e.getSizeBytes());
                    }

                    if (e.isFiltered()) {
                        pipelineMetrics.recordFiltered();
                    }

                    if (e.getDestinations() != null && !e.getDestinations().isEmpty()) {
                        pipelineMetrics.recordRouted();
                    }

                    routeToDestinations(e);
                })
                .doOnError(e -> {
                    long durationNanos = System.nanoTime() - startTimeNanos;
                    pipelineMetrics.recordProcessing(durationNanos, false);
                    log.error("Error processing log entry", e);
                });
    }

    public Flux<LogEntry> processBatch(Flux<LogEntry> entries) {
        return entries
                .onBackpressureBuffer(10000)
                .flatMap(this::process, 100)
                .parallel()
                .runOn(Schedulers.parallel())
                .sequential();
    }

    public Flux<LogEntry> processStream(Flux<String> rawLogs) {
        return rawLogs
                .map(raw -> LogEntry.builder().message(raw).build())
                .transform(this::processBatch);
    }

    private void routeToDestinations(LogEntry entry) {
        List<String> destinations = entry.getDestinations();
        if (destinations == null || destinations.isEmpty()) {
            return;
        }

        for (String destination : destinations) {
            Consumer<LogEntry> route = registeredRoutes.get(destination);
            if (route != null) {
                try {
                    route.accept(entry);
                } catch (Exception e) {
                    log.warn("Failed to route log entry to destination: {}", destination, e);
                }
            }
        }
    }

    public void registerRoute(String destination, Consumer<LogEntry> consumer) {
        registeredRoutes.put(destination, consumer);
        log.info("Registered route for destination: {}", destination);
    }

    public void unregisterRoute(String destination) {
        registeredRoutes.remove(destination);
        log.info("Unregistered route for destination: {}", destination);
    }

    public Map<String, Object> getPipelineStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>(pipelineMetrics.getStats());
        stats.put("stageLatency", latencyTracker.getStageLatencyStats());
        return stats;
    }

    public Mono<Map<String, Object>> getCurrentStatus() {
        return statusMonitor.getCurrentStatus();
    }

    public void resetLatencyStats() {
        latencyTracker.resetStats();
    }

    public List<String> getAvailableProcessors() {
        return defaultPipeline.getStageNames();
    }

    public Pipeline<LogEntry> createCustomPipeline(String name, List<String> stageNames) {
        return pipelineFactory.createLogPipeline(name);
    }

    public long getProcessedCount() {
        return pipelineMetrics.getTotalProcessed().get();
    }

    public long getErrorCount() {
        return pipelineMetrics.getTotalErrors().get();
    }

    public boolean isHealthy() {
        return statusMonitor.isHealthy();
    }
}
