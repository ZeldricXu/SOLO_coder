package com.cicd.server.runner;

import com.cicd.common.enums.PipelineStatus;
import com.cicd.server.entity.JobEvent;
import com.cicd.server.entity.JobExecution;
import com.cicd.server.entity.StepExecution;
import com.cicd.server.pipeline.PipelineOrchestrator;
import com.cicd.server.repository.JobEventRepository;
import com.cicd.server.repository.JobExecutionRepository;
import com.cicd.server.repository.StepExecutionRepository;
import com.cicd.server.websocket.LogWebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobEventStore {

    private final JobEventRepository eventRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final PipelineOrchestrator orchestrator;
    private final LogWebSocketService logWebSocketService;

    private final Map<Long, String> jobTokenCache = new ConcurrentHashMap<>();

    public String generateJobToken(Long jobId) {
        String token = UUID.randomUUID().toString().replace("-", "") + Long.toHexString(jobId);
        jobTokenCache.put(jobId, token);
        return token;
    }

    public boolean validateJobToken(Long jobId, String token) {
        String cached = jobTokenCache.get(jobId);
        if (cached != null && cached.equals(token)) {
            return true;
        }
        return eventRepository.existsByJobToken(token);
    }

    @Transactional
    public void appendEvent(String jobToken, Long jobId, String eventType,
                            Integer stepIndex, String stepName, String stepStatus,
                            Long runnerId, String logIncrement, Integer logOffset,
                            Integer exitCode, String errorMessage) {
        JobEvent event = new JobEvent();
        event.setJobId(jobId);
        event.setJobToken(jobToken);
        event.setEventType(eventType);
        event.setStepIndex(stepIndex);
        event.setStepName(stepName);
        event.setStepStatus(stepStatus);
        event.setRunnerId(runnerId);
        event.setLogIncrement(logIncrement);
        event.setLogOffset(logOffset);
        event.setExitCode(exitCode);
        event.setErrorMessage(errorMessage);
        event.setEventTimestamp(LocalDateTime.now());
        eventRepository.save(event);

        applyEvent(event);
    }

    private void applyEvent(JobEvent event) {
        switch (event.getEventType()) {
            case "JOB_ASSIGNED" -> handleJobAssigned(event);
            case "STEP_STARTED" -> handleStepStarted(event);
            case "STEP_LOG_CHUNK" -> handleStepLogChunk(event);
            case "STEP_COMPLETED" -> handleStepCompleted(event);
            case "STEP_FAILED" -> handleStepFailed(event);
            case "JOB_COMPLETED" -> handleJobCompleted(event);
            default -> log.warn("Unknown event type: {}", event.getEventType());
        }
    }

    private void handleJobAssigned(JobEvent event) {
        log.info("Job {} assigned to runner {}", event.getJobId(), event.getRunnerId());
    }

    private void handleStepStarted(JobEvent event) {
        Long stepId = resolveStepId(event.getJobId(), event.getStepIndex());
        if (stepId == null) return;

        StepExecution step = stepExecutionRepository.findById(stepId).orElse(null);
        if (step == null) return;

        if (step.getStartedAt() == null) {
            step.setStartedAt(event.getEventTimestamp());
        }
        step.setStatus(PipelineStatus.RUNNING);
        stepExecutionRepository.save(step);

        orchestrator.onStepUpdate(stepId, PipelineStatus.RUNNING, null, null);
    }

    private void handleStepLogChunk(JobEvent event) {
        if (event.getLogIncrement() == null) return;
        Long stepId = resolveStepId(event.getJobId(), event.getStepIndex());
        if (stepId == null) return;

        logWebSocketService.broadcastLog(event.getJobId(), stepId, event.getLogIncrement());
    }

    private void handleStepCompleted(JobEvent event) {
        Long stepId = resolveStepId(event.getJobId(), event.getStepIndex());
        if (stepId == null) return;

        StepExecution step = stepExecutionRepository.findById(stepId).orElse(null);
        if (step == null) return;

        step.setStatus(PipelineStatus.SUCCESS);
        step.setFinishedAt(event.getEventTimestamp());
        if (step.getStartedAt() != null) {
            step.setDurationSeconds(java.time.Duration.between(step.getStartedAt(), step.getFinishedAt()).getSeconds());
        }
        step.setExitCode(event.getExitCode());
        stepExecutionRepository.save(step);

        orchestrator.onStepUpdate(stepId, PipelineStatus.SUCCESS, null, event.getExitCode());
    }

    private void handleStepFailed(JobEvent event) {
        Long stepId = resolveStepId(event.getJobId(), event.getStepIndex());
        if (stepId == null) return;

        StepExecution step = stepExecutionRepository.findById(stepId).orElse(null);
        if (step == null) return;

        step.setStatus(PipelineStatus.FAILED);
        step.setFinishedAt(event.getEventTimestamp());
        if (step.getStartedAt() != null) {
            step.setDurationSeconds(java.time.Duration.between(step.getStartedAt(), step.getFinishedAt()).getSeconds());
        }
        step.setExitCode(event.getExitCode());
        stepExecutionRepository.save(step);

        orchestrator.onStepUpdate(stepId, PipelineStatus.FAILED, null, event.getExitCode());
    }

    private void handleJobCompleted(JobEvent event) {
        boolean success = "STEP_COMPLETED".equals(event.getStepStatus()) || event.getExitCode() == null || event.getExitCode() == 0;
        orchestrator.onJobCompleted(event.getJobId(), success, null);
        jobTokenCache.remove(event.getJobId());
    }

    private Long resolveStepId(Long jobId, Integer stepIndex) {
        if (stepIndex == null) return null;
        JobExecution job = jobExecutionRepository.findById(jobId).orElse(null);
        if (job == null) return null;
        List<StepExecution> steps = stepExecutionRepository.findByJobExecutionIdOrderByStepOrder(jobId);
        if (stepIndex < steps.size()) {
            return steps.get(stepIndex).getId();
        }
        return null;
    }

    @Transactional(readOnly = true)
    public JobStateRecovery recoverJobState(Long jobId) {
        List<JobEvent> events = eventRepository.findByJobIdOrderByEventTimestampAsc(jobId);
        if (events.isEmpty()) {
            return new JobStateRecovery(false, null, -1, null, 0, false);
        }

        String lastJobStatus = "PENDING";
        int lastCompletedStep = -1;
        String lastStepStatus = null;
        long lastEventTimestamp = 0;
        boolean needsResend = false;

        for (JobEvent event : events) {
            lastEventTimestamp = java.time.Duration.between(
                LocalDateTime.of(1970, 1, 1, 0, 0),
                event.getEventTimestamp()
            ).getSeconds();

            switch (event.getEventType()) {
                case "JOB_ASSIGNED" -> lastJobStatus = "RUNNING";
                case "STEP_STARTED" -> lastJobStatus = "RUNNING";
                case "STEP_COMPLETED" -> {
                    lastCompletedStep = event.getStepIndex() != null ? event.getStepIndex() : lastCompletedStep;
                    lastStepStatus = "SUCCESS";
                }
                case "STEP_FAILED" -> {
                    lastCompletedStep = event.getStepIndex() != null ? event.getStepIndex() : lastCompletedStep;
                    lastStepStatus = "FAILED";
                    lastJobStatus = "FAILED";
                }
                case "JOB_COMPLETED" -> {
                    lastJobStatus = event.getExitCode() != null && event.getExitCode() == 0 ? "SUCCESS" : "FAILED";
                }
            }
        }

        if ("RUNNING".equals(lastJobStatus)) {
            long secondsSinceLastEvent = java.time.Duration.between(
                events.get(events.size() - 1).getEventTimestamp(),
                LocalDateTime.now()
            ).getSeconds();
            needsResend = secondsSinceLastEvent > 120;
        }

        return new JobStateRecovery(
            true, lastJobStatus, lastCompletedStep, lastStepStatus, lastEventTimestamp, needsResend
        );
    }

    public record JobStateRecovery(
        boolean found,
        String jobStatus,
        int lastCompletedStep,
        String lastStepStatus,
        long lastEventTimestamp,
        boolean needsResend
    ) {}
}
