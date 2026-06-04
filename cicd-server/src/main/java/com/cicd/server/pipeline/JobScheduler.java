package com.cicd.server.pipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobScheduler {

    private final BlockingQueue<SchedulerEvent> eventQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "job-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    private PipelineOrchestrator orchestrator;

    @PostConstruct
    public void start() {
        running.set(true);
        executor.submit(this::eventLoop);
        log.info("JobScheduler started with single-threaded event loop");
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        executor.shutdownNow();
        log.info("JobScheduler stopped");
    }

    public void setOrchestrator(PipelineOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    private void eventLoop() {
        while (running.get()) {
            try {
                SchedulerEvent event = eventQueue.poll(1, TimeUnit.SECONDS);
                if (event != null) {
                    processEvent(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing event in JobScheduler", e);
            }
        }
    }

    private void processEvent(SchedulerEvent event) {
        try {
            switch (event.type()) {
                case JOB_COMPLETED -> handleJobCompleted((JobCompletedEvent) event);
                case JOB_STARTED -> handleJobStarted((JobStartedEvent) event);
                case STAGE_PROCESS -> handleStageProcess((StageProcessEvent) event);
                default -> log.warn("Unknown event type: {}", event.type());
            }
        } catch (Exception e) {
            log.error("Failed to process {} event", event.type(), e);
        }
    }

    private void handleJobCompleted(JobCompletedEvent event) {
        if (orchestrator == null) {
            log.warn("Orchestrator not set, deferring job completion handling");
            return;
        }
        orchestrator.handleJobCompletedInternal(event.jobId(), event.success(), event.output());
    }

    private void handleJobStarted(JobStartedEvent event) {
        if (orchestrator == null) {
            log.warn("Orchestrator not set, deferring job start handling");
            return;
        }
        orchestrator.handleJobStartedInternal(event.jobId(), event.runnerId());
    }

    private void handleStageProcess(StageProcessEvent event) {
        if (orchestrator == null) {
            log.warn("Orchestrator not set, deferring stage processing");
            return;
        }
        orchestrator.checkStageCompletionInternal(event.executionId(), event.stageId());
    }

    public void onJobCompleted(Long jobId, boolean success, String output) {
        eventQueue.offer(new JobCompletedEvent(jobId, success, output));
    }

    public void onJobStarted(Long jobId, Long runnerId) {
        eventQueue.offer(new JobStartedEvent(jobId, runnerId));
    }

    public void processNextStage(Long executionId, Long stageId) {
        eventQueue.offer(new StageProcessEvent(executionId, stageId));
    }

    public int queueSize() {
        return eventQueue.size();
    }

    public sealed interface SchedulerEvent {
        EventType type();
    }

    public enum EventType {
        JOB_COMPLETED, JOB_STARTED, STAGE_PROCESS
    }

    public record JobCompletedEvent(Long jobId, boolean success, String output) implements SchedulerEvent {
        @Override public EventType type() { return EventType.JOB_COMPLETED; }
    }

    public record JobStartedEvent(Long jobId, Long runnerId) implements SchedulerEvent {
        @Override public EventType type() { return EventType.JOB_STARTED; }
    }

    public record StageProcessEvent(Long executionId, Long stageId) implements SchedulerEvent {
        @Override public EventType type() { return EventType.STAGE_PROCESS; }
    }
}
