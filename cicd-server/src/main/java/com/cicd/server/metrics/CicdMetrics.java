package com.cicd.server.metrics;

import io.micrometer.core.instrument.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class CicdMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger runningJobs = new AtomicInteger(0);
    private final AtomicInteger queuedJobs = new AtomicInteger(0);
    private final AtomicInteger activeRunners = new AtomicInteger(0);
    private final AtomicInteger totalRunners = new AtomicInteger(0);

    private Counter pipelinesTriggeredCounter;
    private Counter pipelinesSuccessCounter;
    private Counter pipelinesFailedCounter;
    private DistributionSummary pipelineDurationSummary;

    @PostConstruct
    public void init() {
        pipelinesTriggeredCounter = Counter.builder("cicd_pipelines_triggered_total")
            .description("Total number of pipeline triggers")
            .register(meterRegistry);

        pipelinesSuccessCounter = Counter.builder("cicd_pipelines_success_total")
            .description("Total number of successful pipelines")
            .register(meterRegistry);

        pipelinesFailedCounter = Counter.builder("cicd_pipelines_failed_total")
            .description("Total number of failed pipelines")
            .register(meterRegistry);

        pipelineDurationSummary = DistributionSummary.builder("cicd_pipelines_duration_seconds")
            .description("Pipeline execution duration in seconds")
            .register(meterRegistry);

        Gauge.builder("cicd_jobs_running", runningJobs, AtomicInteger::get)
            .description("Number of currently running jobs")
            .register(meterRegistry);

        Gauge.builder("cicd_jobs_queued", queuedJobs, AtomicInteger::get)
            .description("Number of jobs waiting in queue")
            .register(meterRegistry);

        Gauge.builder("cicd_runners_active", activeRunners, AtomicInteger::get)
            .description("Number of active runners")
            .register(meterRegistry);

        Gauge.builder("cicd_runners_total", totalRunners, AtomicInteger::get)
            .description("Total number of registered runners")
            .register(meterRegistry);

        log.info("CI/CD Prometheus metrics registered");
    }

    public void onPipelineTriggered() {
        pipelinesTriggeredCounter.increment();
    }

    public void onPipelineSuccess(double durationSeconds) {
        pipelinesSuccessCounter.increment();
        pipelineDurationSummary.record(durationSeconds);
    }

    public void onPipelineFailed(double durationSeconds) {
        pipelinesFailedCounter.increment();
        pipelineDurationSummary.record(durationSeconds);
    }

    public void setRunningJobs(int count) {
        runningJobs.set(count);
    }

    public void setQueuedJobs(int count) {
        queuedJobs.set(count);
    }

    public void setActiveRunners(int count) {
        activeRunners.set(count);
    }

    public void setTotalRunners(int count) {
        totalRunners.set(count);
    }
}
