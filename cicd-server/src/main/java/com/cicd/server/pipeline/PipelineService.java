package com.cicd.server.pipeline;

import com.cicd.common.dto.pipeline.PipelineDefinition;
import com.cicd.common.enums.PipelineStatus;
import com.cicd.common.enums.TriggerType;
import com.cicd.common.util.VariableSubstitutor;
import com.cicd.common.util.YamlParser;
import com.cicd.server.entity.*;
import com.cicd.server.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    private final PipelineRepository pipelineRepository;
    private final PipelineExecutionRepository executionRepository;
    private final StageExecutionRepository stageExecutionRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final PipelineTemplateRepository templateRepository;
    private final PipelineScheduler pipelineScheduler;
    private final PipelineOrchestrator orchestrator;
    private final com.cicd.server.metrics.CicdMetrics cicdMetrics;

    public Pipeline createPipeline(Long projectId, String name, String description, String yamlDefinition, Long templateId) {
        Project project = new Project();
        project.setId(projectId);

        Pipeline pipeline = new Pipeline();
        pipeline.setProject(project);
        pipeline.setName(name);
        pipeline.setDescription(description);
        pipeline.setYamlDefinition(yamlDefinition);
        pipeline.setTemplateId(templateId);
        pipeline.setIsActive(true);

        return pipelineRepository.save(pipeline);
    }

    public Pipeline updatePipeline(Long id, String name, String description, String yamlDefinition) {
        Pipeline pipeline = pipelineRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Pipeline not found: " + id));

        pipeline.setName(name);
        pipeline.setDescription(description);
        pipeline.setYamlDefinition(yamlDefinition);

        return pipelineRepository.save(pipeline);
    }

    public void deletePipeline(Long id) {
        pipelineRepository.deleteById(id);
    }

    public Pipeline getPipeline(Long id) {
        return pipelineRepository.findById(id).orElse(null);
    }

    public Page<PipelineExecution> getExecutions(Long pipelineId, Pageable pageable) {
        return executionRepository.findByPipelineId(pipelineId, pageable);
    }

    public PipelineExecution getExecution(Long executionId) {
        return executionRepository.findById(executionId).orElse(null);
    }

    @Transactional
    public PipelineExecution triggerPipeline(Long pipelineId, TriggerType triggerType, String triggeredBy,
                                             String branchName, Map<String, String> params) {
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
            .orElseThrow(() -> new IllegalArgumentException("Pipeline not found: " + pipelineId));

        PipelineDefinition definition;
        try {
            definition = YamlParser.parse(pipeline.getYamlDefinition());
        } catch (YamlParser.PipelineValidationException e) {
            throw new IllegalArgumentException("Invalid pipeline YAML: " + e.getMessage(), e);
        }

        Integer nextNumber = executionRepository.findMaxExecutionNumberByPipelineId(pipelineId);
        nextNumber = nextNumber == null ? 1 : nextNumber + 1;

        Map<String, String> allParams = new HashMap<>();
        if (definition.getVariables() != null) {
            allParams.putAll(definition.getVariables());
        }
        if (params != null) {
            allParams.putAll(params);
        }
        allParams.put("PIPELINE_NAME", pipeline.getName());
        allParams.put("BUILD_NUMBER", String.valueOf(nextNumber));
        if (branchName != null) {
            allParams.put("BRANCH_NAME", branchName);
        }

        PipelineExecution execution = new PipelineExecution();
        execution.setPipeline(pipeline);
        execution.setProject(pipeline.getProject());
        execution.setExecutionNumber(nextNumber);
        execution.setStatus(PipelineStatus.PENDING);
        execution.setTriggerType(triggerType);
        execution.setTriggeredBy(triggeredBy);
        execution.setBranchName(branchName);
        execution.setParamsJson(serializeParams(allParams));

        execution = executionRepository.save(execution);

        createStageExecutions(execution, definition, allParams);

        pipeline.setLatestStatus(PipelineStatus.PENDING);
        pipeline.setLatestExecutionId(execution.getId());
        pipelineRepository.save(pipeline);

        orchestrator.startExecution(execution.getId());

        cicdMetrics.onPipelineTriggered();

        return execution;
    }

    private void createStageExecutions(PipelineExecution execution, PipelineDefinition definition, Map<String, String> params) {
        int stageOrder = 0;
        for (var stageDef : definition.getStages()) {
            StageExecution stage = new StageExecution();
            stage.setExecution(execution);
            stage.setStageName(VariableSubstitutor.substitute(stageDef.getName(), params));
            stage.setStageOrder(stageOrder++);
            stage.setStatus(PipelineStatus.PENDING);
            stage = stageExecutionRepository.save(stage);

            int jobOrder = 0;
            for (var jobDef : stageDef.getJobs()) {
                JobExecution job = new JobExecution();
                job.setStageExecution(stage);
                job.setJobName(VariableSubstitutor.substitute(jobDef.getName(), params));
                job.setJobOrder(jobOrder++);
                job.setStatus(PipelineStatus.PENDING);
                job.setRunnerTags(String.join(",", jobDef.getTags() != null ? List.of(jobDef.getTags()) : List.of()));
                job = jobExecutionRepository.save(job);

                int stepOrder = 0;
                for (var stepDef : jobDef.getSteps()) {
                    StepExecution step = new StepExecution();
                    step.setJobExecution(job);
                    step.setStepName(VariableSubstitutor.substitute(stepDef.getName(), params));
                    step.setStepOrder(stepOrder++);
                    step.setType(stepDef.getType());
                    step.setStatus(PipelineStatus.PENDING);
                    step.setCommand(buildCommand(stepDef, params));
                    stepExecutionRepository.save(step);
                }
            }
        }
    }

    private String buildCommand(com.cicd.common.dto.pipeline.PipelineStep stepDef, Map<String, String> params) {
        return switch (stepDef.getType()) {
            case RUN -> VariableSubstitutor.substitute(stepDef.getRun(), params);
            case SCRIPT -> VariableSubstitutor.substitute(stepDef.getScript(), params);
            case DOCKER -> stepDef.getDocker() != null ?
                "docker build -t " + String.join(",", stepDef.getDocker().getTags()) + " " + stepDef.getDocker().getContext() : null;
            case PUSH -> stepDef.getPush() != null ?
                "docker push " + stepDef.getPush().getRepository() : null;
            case DEPLOY -> "deploy to " + (stepDef.getDeploy() != null ? stepDef.getDeploy().getEnvironment() : "");
            case KUBECTL -> "kubectl " + (stepDef.getKubectl() != null ? stepDef.getKubectl().getCommand() : "");
            case CALL_WEBHOOK -> "curl " + (stepDef.getWebhook() != null ? stepDef.getWebhook().getUrl() : "");
        };
    }

    public void cancelExecution(Long executionId, String reason) {
        PipelineExecution execution = executionRepository.findById(executionId)
            .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + executionId));

        execution.setStatus(PipelineStatus.CANCELLED);
        execution.setCancelReason(reason);
        execution.setFinishedAt(LocalDateTime.now());
        execution.setDurationSeconds(java.time.Duration.between(execution.getStartedAt(), execution.getFinishedAt()).getSeconds());
        executionRepository.save(execution);

        orchestrator.cancelExecution(executionId);
    }

    @Transactional
    public void updateExecutionStatus(Long executionId, PipelineStatus status) {
        PipelineExecution execution = executionRepository.findById(executionId).orElseThrow();
        execution.setStatus(status);
        if (status == PipelineStatus.RUNNING && execution.getStartedAt() == null) {
            execution.setStartedAt(LocalDateTime.now());
        }
        if (status == PipelineStatus.SUCCESS || status == PipelineStatus.FAILED || status == PipelineStatus.CANCELLED) {
            execution.setFinishedAt(LocalDateTime.now());
            if (execution.getStartedAt() != null) {
                execution.setDurationSeconds(java.time.Duration.between(execution.getStartedAt(), execution.getFinishedAt()).getSeconds());
            }
        }
        executionRepository.save(execution);

        Pipeline pipeline = execution.getPipeline();
        pipeline.setLatestStatus(status);
        pipelineRepository.save(pipeline);
    }

    private String serializeParams(Map<String, String> params) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> deserializeParams(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    public void schedulePipeline(Long pipelineId, String cronExpression, Map<String, String> params) {
        pipelineScheduler.schedulePipeline(pipelineId, cronExpression, params);
    }

    public void unschedulePipeline(Long pipelineId) {
        pipelineScheduler.unschedulePipeline(pipelineId);
    }
}
