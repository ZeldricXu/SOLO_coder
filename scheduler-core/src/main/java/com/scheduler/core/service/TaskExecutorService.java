package com.scheduler.core.service;

import com.scheduler.common.event.EventPublisher;
import com.scheduler.common.exception.BusinessException;
import com.scheduler.data.repository.ConfigRepository;
import com.scheduler.data.repository.ScheduledTaskRepository;
import com.scheduler.data.repository.TaskExecutionRepository;
import com.scheduler.persistence.entity.ConfigDefinition;
import com.scheduler.persistence.entity.ScheduledTask;
import com.scheduler.persistence.entity.TaskExecution;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutorService {

    private final ScheduledTaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final ConfigRepository configRepository;
    private final EventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    private final Map<String, Semaphore> resourcePools = new ConcurrentHashMap<>();
    private final Map<String, Object> runningTasks = new ConcurrentHashMap<>();

    public Mono<TaskExecution> executeTask(String taskId, Map<String, Object> context) {
        String traceId = context != null && context.containsKey("traceId")
                ? (String) context.get("traceId")
                : UUID.randomUUID().toString();

        return Mono.<TaskExecution>create(sink -> {
            try {
                ScheduledTask task = taskRepository.findById(taskId);

                validateTask(task);

                ConfigDefinition config = null;
                if (task.getConfigId() != null) {
                    config = configRepository.findLatest(task.getConfigId());
                }

                Semaphore pool = acquireResourcePool(task, config);
                try {
                    TaskExecution execution = createExecution(task, traceId, context);
                    runningTasks.put(execution.getRunId(), task);

                    Timer.Sample sample = Timer.start(meterRegistry);

                    try {
                        executionRepository.updateProgress(execution.getRunId(), 0.1, "INITIALIZING");

                        Object result = processCore(task, execution, config);

                        execution.setResult(result != null ? Map.of("output", result) : null);
                        execution = executionRepository.complete(execution.getRunId(), true, null);

                        persistResult(execution);

                        eventPublisher.publish(new com.scheduler.common.event.BaseEvent(this, "task.completed")
                                .payload("taskId", taskId)
                                .payload("runId", execution.getRunId()));

                        sample.stop(meterRegistry.timer("task.execution", "taskId", taskId));

                        sink.success(execution);
                    } finally {
                        runningTasks.remove(execution.getRunId());
                        pool.release();
                    }

                } catch (BusinessException e) {
                    handleExecutionError(taskId, traceId, e);
                    sink.error(e);
                } catch (Exception e) {
                    rollbackTransaction(traceId);
                    handleExecutionError(taskId, traceId, e);
                    sink.error(BusinessException.internalError("Internal processing error: " + e.getMessage()));
                }
            } catch (Exception e) {
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void validateTask(ScheduledTask task) {
        if (task == null) {
            throw BusinessException.notFound("Task not found");
        }
        if (!"ACTIVE".equals(task.getStatus())) {
            throw BusinessException.validationError("Task is not active");
        }
    }

    private Semaphore acquireResourcePool(ScheduledTask task, ConfigDefinition config) {
        int poolSize = config != null && config.getParameters() != null
                ? (Integer) config.getParameters().getOrDefault("poolSize", 10)
                : 10;

        String poolKey = task.getNamespace() != null ? task.getNamespace() : "default";
        Semaphore pool = resourcePools.computeIfAbsent(poolKey, k -> new Semaphore(poolSize));

        try {
            pool.acquire();
            log.debug("Acquired resource from pool: {}", poolKey);
            return pool;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BusinessException.internalError("Failed to acquire resource");
        }
    }

    private TaskExecution createExecution(ScheduledTask task, String traceId, Map<String, Object> context) {
        TaskExecution execution = new TaskExecution();
        execution.setTaskId(task.getTaskId());
        execution.setScheduledBy(task.getCreatedBy());
        execution.setContext(context != null ? new HashMap<>(context) : new HashMap<>());
        if (traceId != null) {
            execution.getContext().put("traceId", traceId);
        }
        return executionRepository.create(execution);
    }

    private Object processCore(ScheduledTask task, TaskExecution execution, ConfigDefinition config) throws Exception {
        log.info("Processing task: {} (runId: {})", task.getTaskId(), execution.getRunId());

        executionRepository.updateProgress(execution.getRunId(), 0.3, "PROCESSING");

        Thread.sleep(100);

        executionRepository.updateProgress(execution.getRunId(), 0.6, "EXECUTING");

        Thread.sleep(100);

        executionRepository.updateProgress(execution.getRunId(), 0.9, "FINALIZING");

        return Map.of(
                "taskId", task.getTaskId(),
                "runId", execution.getRunId(),
                "completedAt", Instant.now().toString(),
                "parameters", task.getParameters()
        );
    }

    private void persistResult(TaskExecution execution) {
        log.debug("Persisted result for runId: {}", execution.getRunId());
    }

    private void rollbackTransaction(String traceId) {
        log.warn("Rolling back transaction for traceId: {}", traceId);
    }

    private void handleExecutionError(String taskId, String traceId, Exception e) {
        log.error("Task execution failed: taskId={}, traceId={}", taskId, traceId, e);
    }

    public int getRunningTaskCount() {
        return runningTasks.size();
    }

    public Map<String, Object> getRunningTasks() {
        return new HashMap<>(runningTasks);
    }
}
