package com.cicd.server.pipeline;

import com.cicd.common.enums.PipelineStatus;
import com.cicd.server.entity.*;
import com.cicd.server.repository.*;
import com.cicd.server.runner.RunnerManager;
import com.cicd.server.websocket.LogWebSocketService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineOrchestrator {

    private final PipelineExecutionRepository executionRepository;
    private final StageExecutionRepository stageExecutionRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final RunnerManager runnerManager;
    private final LogWebSocketService logWebSocketService;
    private final PipelineNotificationService notificationService;
    private final JobScheduler jobScheduler;

    private final Map<Long, Set<Long>> cancelledExecutions = new HashMap<>();
    private final Map<Long, Set<Long>> jobDependencies = new HashMap<>();

    @PostConstruct
    public void init() {
        jobScheduler.setOrchestrator(this);
    }

    public void startExecution(Long executionId) {
        PipelineExecution execution = executionRepository.findById(executionId).orElseThrow();
        if (execution.getStatus() != PipelineStatus.PENDING) {
            log.warn("Execution {} is not in PENDING state, cannot start", executionId);
            return;
        }

        execution.setStatus(PipelineStatus.RUNNING);
        execution.setStartedAt(LocalDateTime.now());
        executionRepository.save(execution);

        processNextStage(executionId);
    }

    public void processNextStage(Long executionId) {
        if (cancelledExecutions.containsKey(executionId)) {
            log.info("Execution {} is cancelled, stopping processing", executionId);
            cancelledExecutions.remove(executionId);
            return;
        }

        List<StageExecution> stages = stageExecutionRepository.findByExecutionId(executionId);
        stages.sort(Comparator.comparingInt(StageExecution::getStageOrder));

        for (StageExecution stage : stages) {
            if (stage.getStatus() == PipelineStatus.PENDING) {
                startStage(executionId, stage);
                return;
            } else if (stage.getStatus() == PipelineStatus.RUNNING) {
                jobScheduler.processNextStage(executionId, stage.getId());
                return;
            } else if (stage.getStatus() == PipelineStatus.FAILED) {
                failExecution(executionId, "Stage " + stage.getStageName() + " failed");
                return;
            }
        }

        completeExecution(executionId);
    }

    private void startStage(Long executionId, StageExecution stage) {
        PipelineExecution execution = executionRepository.findById(executionId).orElseThrow();

        stage.setStatus(PipelineStatus.RUNNING);
        stage.setStartedAt(LocalDateTime.now());
        stageExecutionRepository.save(stage);

        log.info("Starting stage: {} for execution: {}", stage.getStageName(), executionId);

        List<JobExecution> jobs = jobExecutionRepository.findByStageExecutionId(stage.getId());
        jobs.sort(Comparator.comparingInt(JobExecution::getJobOrder));

        Map<String, String> params = extractParams(execution);

        for (JobExecution job : jobs) {
            String jobTags = job.getRunnerTags();
            String[] tags = jobTags != null && !jobTags.isEmpty() ? jobTags.split(",") : new String[0];

            try {
                Long runnerId = runnerManager.assignJob(job.getId(), tags, params);
                if (runnerId != null) {
                    job.setRunnerId(runnerId);
                    job.setStatus(PipelineStatus.RUNNING);
                    job.setStartedAt(LocalDateTime.now());
                    jobExecutionRepository.save(job);

                    log.info("Assigned job {} to runner {}", job.getId(), runnerId);
                } else {
                    log.warn("No available runner for job {}, tags: {}", job.getId(), Arrays.toString(tags));
                }
            } catch (Exception e) {
                log.error("Failed to assign job {}", job.getId(), e);
                job.setStatus(PipelineStatus.FAILED);
                job.setFinishedAt(LocalDateTime.now());
                jobExecutionRepository.save(job);
            }
        }
    }

    void checkStageCompletionInternal(Long executionId, Long stageId) {
        StageExecution stage = stageExecutionRepository.findById(stageId).orElse(null);
        if (stage == null) {
            log.warn("Stage {} not found for execution {}", stageId, executionId);
            return;
        }

        List<JobExecution> jobs = jobExecutionRepository.findByStageExecutionId(stage.getId());

        boolean allCompleted = true;
        boolean anyFailed = false;

        for (JobExecution job : jobs) {
            if (job.getStatus() == PipelineStatus.PENDING || job.getStatus() == PipelineStatus.RUNNING) {
                allCompleted = false;
            } else if (job.getStatus() == PipelineStatus.FAILED) {
                anyFailed = true;
            }
        }

        if (allCompleted) {
            stage.setStatus(anyFailed ? PipelineStatus.FAILED : PipelineStatus.SUCCESS);
            stage.setFinishedAt(LocalDateTime.now());
            if (stage.getStartedAt() != null) {
                stage.setDurationSeconds(java.time.Duration.between(stage.getStartedAt(), stage.getFinishedAt()).getSeconds());
            }
            stageExecutionRepository.save(stage);

            if (anyFailed) {
                failExecution(executionId, "Some jobs failed in stage " + stage.getStageName());
            } else {
                processNextStage(executionId);
            }
        }
    }

    public void onJobCompleted(Long jobId, boolean success, String output) {
        jobScheduler.onJobCompleted(jobId, success, output);
    }

    void handleJobCompletedInternal(Long jobId, boolean success, String output) {
        JobExecution job = jobExecutionRepository.findById(jobId).orElseThrow();

        job.setStatus(success ? PipelineStatus.SUCCESS : PipelineStatus.FAILED);
        job.setFinishedAt(LocalDateTime.now());
        if (job.getStartedAt() != null) {
            job.setDurationSeconds(java.time.Duration.between(job.getStartedAt(), job.getFinishedAt()).getSeconds());
        }
        jobExecutionRepository.save(job);

        runnerManager.releaseJob(jobId, job.getRunnerId());

        StageExecution stage = job.getStageExecution();
        checkStageCompletionInternal(stage.getExecution().getId(), stage.getId());
    }

    void handleJobStartedInternal(Long jobId, Long runnerId) {
        JobExecution job = jobExecutionRepository.findById(jobId).orElse(null);
        if (job != null) {
            job.setRunnerId(runnerId);
            job.setStatus(PipelineStatus.RUNNING);
            job.setStartedAt(LocalDateTime.now());
            jobExecutionRepository.save(job);
        }
    }

    public void onStepUpdate(Long stepId, PipelineStatus status, String logLine, Integer exitCode) {
        StepExecution step = stepExecutionRepository.findById(stepId).orElseThrow();
        step.setStatus(status);

        if (status == PipelineStatus.RUNNING && step.getStartedAt() == null) {
            step.setStartedAt(LocalDateTime.now());
        }

        if (status == PipelineStatus.SUCCESS || status == PipelineStatus.FAILED) {
            step.setFinishedAt(LocalDateTime.now());
            if (step.getStartedAt() != null) {
                step.setDurationSeconds(java.time.Duration.between(step.getStartedAt(), step.getFinishedAt()).getSeconds());
            }
            step.setExitCode(exitCode);
        }

        if (logLine != null) {
            String currentOutput = step.getOutput() != null ? step.getOutput() : "";
            step.setOutput(currentOutput + logLine + "\n");
        }

        stepExecutionRepository.save(step);

        if (logLine != null) {
            Long jobId = step.getJobExecution().getId();
            logWebSocketService.broadcastLog(jobId, stepId, logLine);
        }
    }

    private void completeExecution(Long executionId) {
        PipelineExecution execution = executionRepository.findById(executionId).orElseThrow();
        execution.setStatus(PipelineStatus.SUCCESS);
        execution.setFinishedAt(LocalDateTime.now());
        if (execution.getStartedAt() != null) {
            execution.setDurationSeconds(java.time.Duration.between(execution.getStartedAt(), execution.getFinishedAt()).getSeconds());
        }
        executionRepository.save(execution);

        log.info("Execution {} completed successfully", executionId);

        Pipeline pipeline = execution.getPipeline();
        pipeline.setLatestStatus(PipelineStatus.SUCCESS);
        pipelineRepositorySave(pipeline);

        notificationService.onPipelineCompleted(execution, true);
    }

    private void failExecution(Long executionId, String errorMessage) {
        PipelineExecution execution = executionRepository.findById(executionId).orElseThrow();
        execution.setStatus(PipelineStatus.FAILED);
        execution.setErrorMessage(errorMessage);
        execution.setFinishedAt(LocalDateTime.now());
        if (execution.getStartedAt() != null) {
            execution.setDurationSeconds(java.time.Duration.between(execution.getStartedAt(), execution.getFinishedAt()).getSeconds());
        }
        executionRepository.save(execution);

        log.error("Execution {} failed: {}", executionId, errorMessage);

        Pipeline pipeline = execution.getPipeline();
        pipeline.setLatestStatus(PipelineStatus.FAILED);
        pipelineRepositorySave(pipeline);

        notificationService.onPipelineCompleted(execution, false);
    }

    public void cancelExecution(Long executionId) {
        cancelledExecutions.put(executionId, new HashSet<>());

        PipelineExecution execution = executionRepository.findById(executionId).orElseThrow();
        List<StageExecution> stages = stageExecutionRepository.findByExecutionId(executionId);

        for (StageExecution stage : stages) {
            if (stage.getStatus() == PipelineStatus.RUNNING) {
                List<JobExecution> jobs = jobExecutionRepository.findByStageExecutionId(stage.getId());
                for (JobExecution job : jobs) {
                    if (job.getStatus() == PipelineStatus.RUNNING) {
                        runnerManager.cancelJob(job.getId(), job.getRunnerId());
                    }
                }
            }
        }
    }

    private Map<String, String> extractParams(PipelineExecution execution) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(execution.getParamsJson(), Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private void pipelineRepositorySave(Pipeline pipeline) {
        var repo = SpringContextHolder.getBean(PipelineRepository.class);
        if (repo != null) {
            repo.save(pipeline);
        }
    }

    public void trackJobDependency(Long jobId, Long dependentJobId) {
        jobDependencies.computeIfAbsent(jobId, k -> new HashSet<>()).add(dependentJobId);
    }

    public Set<Long> getJobDependencies(Long jobId) {
        return jobDependencies.getOrDefault(jobId, Collections.emptySet());
    }
}
