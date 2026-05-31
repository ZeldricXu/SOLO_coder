package com.datapipeline.core.persistence;

import com.datapipeline.common.model.RunInstance;
import com.datapipeline.common.model.RunInstance.Phase;
import com.datapipeline.common.tracing.TraceContext;
import com.datapipeline.data.repository.RunInstanceRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

@Slf4j
public class ResultPersister {

    private final RunInstanceRepository runInstanceRepository;

    public ResultPersister(RunInstanceRepository runInstanceRepository) {
        this.runInstanceRepository = runInstanceRepository;
    }

    public void persistSuccess(String runId, Object result) {
        runInstanceRepository.findById(runId).ifPresent(run -> {
            run.markCompleted();
            runInstanceRepository.save(run);
            log.info("Run instance completed: runId={}", runId);
        });
    }

    public void persistFailure(String runId, String errorDetail) {
        runInstanceRepository.findById(runId).ifPresent(run -> {
            run.markFailed(errorDetail);
            runInstanceRepository.save(run);
            log.error("Run instance failed: runId={}, error={}", runId, errorDetail);
        });
    }

    public void persistTimeout(String runId, String message) {
        runInstanceRepository.findById(runId).ifPresent(run -> {
            run.markTimeout(message);
            runInstanceRepository.save(run);
            log.warn("Run instance timeout: runId={}", runId);
        });
    }

    public void updateProgress(String runId, double progress) {
        runInstanceRepository.findById(runId).ifPresent(run -> {
            run.updateProgress(progress);
            runInstanceRepository.save(run);
            log.debug("Run progress updated: runId={}, progress={}", runId, progress);
        });
    }

    public RunInstance createRun(String entityId) {
        RunInstance run = RunInstance.builder()
                .runId(generateRunId())
                .entityId(entityId)
                .phase(Phase.INITIALIZING)
                .progress(0.0)
                .startedAt(Instant.now())
                .build();
        runInstanceRepository.save(run);
        log.info("Run instance created: runId={}, entityId={}", run.getRunId(), entityId);
        return run;
    }

    public void markRunning(String runId) {
        runInstanceRepository.findById(runId).ifPresent(run -> {
            run.markRunning();
            runInstanceRepository.save(run);
        });
    }

    private String generateRunId() {
        return "run_" + Instant.now().toEpochMilli() + "_" + Math.abs((int) (Math.random() * 10000));
    }

}
