package com.scheduler.data.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.scheduler.common.exception.BusinessException;
import com.scheduler.persistence.entity.TaskExecution;
import com.scheduler.persistence.mapper.TaskExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class TaskExecutionRepository {

    private final TaskExecutionMapper executionMapper;

    public TaskExecution create(TaskExecution execution) {
        execution.setRunId("run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        execution.setPhase("INITIALIZING");
        execution.setProgress(0.0);
        execution.setStartedAt(Instant.now());
        execution.setStatus("RUNNING");
        executionMapper.insert(execution);
        log.debug("Created task execution: {}", execution.getRunId());
        return execution;
    }

    public TaskExecution findByRunId(String runId) {
        TaskExecution execution = executionMapper.findByRunId(runId);
        if (execution == null) {
            throw BusinessException.notFound("Execution not found: " + runId);
        }
        return execution;
    }

    public TaskExecution update(TaskExecution execution) {
        executionMapper.updateById(execution);
        return execution;
    }

    public IPage<TaskExecution> findByTaskId(String taskId, int page, int size) {
        return executionMapper.findByTaskId(new Page<>(page, size), taskId);
    }

    public List<TaskExecution> findByStatus(String status) {
        return executionMapper.findByStatus(status);
    }

    public TaskExecution complete(String runId, boolean success, String errorDetail) {
        TaskExecution execution = findByRunId(runId);
        execution.setCompletedAt(Instant.now());
        execution.setStatus(success ? "COMPLETED" : "FAILED");
        execution.setProgress(1.0);
        execution.setPhase("COMPLETED");
        execution.setDurationMs(java.time.Duration.between(execution.getStartedAt(), execution.getCompletedAt()).toMillis());
        if (!success) {
            execution.setErrorDetail(errorDetail);
        }
        return update(execution);
    }

    public void updateProgress(String runId, double progress, String phase) {
        TaskExecution execution = findByRunId(runId);
        execution.setProgress(progress);
        execution.setPhase(phase);
        update(execution);
    }
}
