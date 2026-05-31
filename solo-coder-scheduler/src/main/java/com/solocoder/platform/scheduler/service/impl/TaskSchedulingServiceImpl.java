package com.solocoder.platform.scheduler.service.impl;

import com.solocoder.platform.common.exception.BusinessException;
import com.solocoder.platform.scheduler.model.TaskDefinition;
import com.solocoder.platform.scheduler.model.TaskExecution;
import com.solocoder.platform.scheduler.service.TaskSchedulingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TaskSchedulingServiceImpl implements TaskSchedulingService {

    private final Map<String, TaskDefinition> taskStore = new ConcurrentHashMap<>();
    private final Map<String, TaskExecution> executionStore = new ConcurrentHashMap<>();

    @Override
    public TaskDefinition createTask(TaskDefinition task) {
        String taskId = task.getTaskId() != null ? task.getTaskId() : UUID.randomUUID().toString();
        TaskDefinition saved = TaskDefinition.builder()
                .taskId(taskId)
                .taskName(task.getTaskName())
                .taskType(task.getTaskType())
                .cronExpression(task.getCronExpression())
                .fixedDelay(task.getFixedDelay())
                .fixedRate(task.getFixedRate())
                .parameters(task.getParameters())
                .maxRetries(task.getMaxRetries() > 0 ? task.getMaxRetries() : 3)
                .retryInterval(task.getRetryInterval() != null ? task.getRetryInterval() : Duration.ofSeconds(30))
                .createdAt(LocalDateTime.now())
                .build();
        taskStore.put(taskId, saved);
        log.info("Task created: id={}, name={}", taskId, saved.getTaskName());
        return saved;
    }

    @Override
    public Optional<TaskDefinition> getTask(String taskId) {
        return Optional.ofNullable(taskStore.get(taskId));
    }

    @Override
    public List<TaskDefinition> listTasks() {
        return new ArrayList<>(taskStore.values());
    }

    @Override
    public void deleteTask(String taskId) {
        taskStore.remove(taskId);
        log.info("Task deleted: id={}", taskId);
    }

    @Override
    public TaskExecution executeTask(String taskId) {
        TaskDefinition task = taskStore.get(taskId);
        if (task == null) {
            throw new BusinessException("Task not found: " + taskId);
        }

        String executionId = UUID.randomUUID().toString();
        LocalDateTime startedAt = LocalDateTime.now();
        TaskExecution execution = TaskExecution.builder()
                .executionId(executionId)
                .taskId(taskId)
                .status(TaskExecution.ExecutionStatus.RUNNING)
                .startedAt(startedAt)
                .retryCount(0)
                .build();
        executionStore.put(executionId, execution);

        try {
            log.info("Executing task: id={}, name={}", taskId, task.getTaskName());
            Thread.sleep(100);

            LocalDateTime completedAt = LocalDateTime.now();
            long durationMs = Duration.between(startedAt, completedAt).toMillis();
            execution.setStatus(TaskExecution.ExecutionStatus.COMPLETED);
            execution.setCompletedAt(completedAt);
            execution.setDurationMs(durationMs);
            execution.setResult("SUCCESS");
            log.info("Task completed: id={}, duration={}ms", taskId, durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            execution.setStatus(TaskExecution.ExecutionStatus.FAILED);
            execution.setErrorMessage("Execution interrupted");
        } catch (Exception e) {
            execution.setStatus(TaskExecution.ExecutionStatus.FAILED);
            execution.setErrorMessage(e.getMessage());
            log.error("Task execution failed: id={}", taskId, e);
        }

        return execution;
    }

    @Override
    public TaskExecution getExecution(String executionId) {
        return executionStore.get(executionId);
    }

    @Override
    public List<TaskExecution> getTaskExecutions(String taskId) {
        return executionStore.values().stream()
                .filter(e -> taskId.equals(e.getTaskId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<TaskExecution> getRunningExecutions() {
        return executionStore.values().stream()
                .filter(e -> e.getStatus() == TaskExecution.ExecutionStatus.RUNNING)
                .collect(Collectors.toList());
    }

    @Override
    public boolean cancelExecution(String executionId) {
        TaskExecution execution = executionStore.get(executionId);
        if (execution == null) return false;
        if (execution.getStatus() != TaskExecution.ExecutionStatus.RUNNING &&
                execution.getStatus() != TaskExecution.ExecutionStatus.PENDING) {
            return false;
        }
        execution.setStatus(TaskExecution.ExecutionStatus.CANCELLED);
        execution.setCompletedAt(LocalDateTime.now());
        log.info("Execution cancelled: id={}", executionId);
        return true;
    }
}
