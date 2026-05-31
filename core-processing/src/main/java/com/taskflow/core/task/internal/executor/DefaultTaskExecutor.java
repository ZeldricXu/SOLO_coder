package com.taskflow.core.task.internal.executor;

import com.taskflow.common.exception.*;
import com.taskflow.common.model.Constants;
import com.taskflow.common.utils.IdGenerator;
import com.taskflow.core.task.api.*;
import com.taskflow.core.task.domain.*;
import com.taskflow.data.entity.RunInstanceEntity;
import com.taskflow.data.service.RunInstanceService;
import com.taskflow.data.service.TaskService;
import com.taskflow.tenant.service.TenantService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 默认任务执行器实现
 * 内部实现，不对外暴露
 * 依赖倒置：依赖TaskHandler接口而非具体实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultTaskExecutor implements TaskExecutor {

    private final TaskRegistry taskRegistry;
    private final TaskService taskService;
    private final RunInstanceService runInstanceService;
    private final TenantService tenantService;
    private final MeterRegistry meterRegistry;
    private final List<TaskLifecycleListener> listeners;

    private final Semaphore resourceSemaphore = new Semaphore(100);
    private static final int MAX_RETRY_ATTEMPTS = 3;

    @Override
    public Mono<TaskResult> execute(TaskRequest request) {
        return Mono.fromCallable(() -> {
            String traceId = request.getTraceId() != null ? request.getTraceId() : IdGenerator.generateTraceId();
            String runId = IdGenerator.generateId("run");
            String tenantId = request.getTenantId() != null ? request.getTenantId() : Constants.DEFAULT_TENANT_ID;

            ExecutionContext context = ExecutionContext.builder()
                    .traceId(traceId)
                    .tenantId(tenantId)
                    .userId(request.getUserId())
                    .runId(runId)
                    .taskId(request.getTaskId())
                    .startTime(LocalDateTime.now())
                    .retryCount(0)
                    .maxRetries(MAX_RETRY_ATTEMPTS)
                    .build();

            notifyStart(context);

            try {
                validateRequest(request);
                Task task = taskService.getById(tenantId, request.getTaskId());

                acquireResource();
                try {
                    TaskResult result = executeWithRetry(request, task, context);
                    notifySuccess(context, result);
                    return result;
                } finally {
                    releaseResource();
                }
            } catch (ValidationException e) {
                notifyFailure(context, e);
                throw e;
            } catch (TimeoutException e) {
                notifyFailure(context, e);
                throw e;
            } catch (ConflictException e) {
                notifyFailure(context, e);
                throw e;
            } catch (Exception e) {
                log.error("Task execution failed, traceId: {}", traceId, e);
                persistFailure(runId, tenantId, request.getTaskId(), e);
                notifyFailure(context, e);
                throw new BusinessException(500, "内部处理错误: " + e.getMessage(), traceId);
            } finally {
                notifyComplete(context);
                recordMetrics(context);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private TaskResult executeWithRetry(TaskRequest request, Task task, ExecutionContext context) throws Exception {
        int maxRetry = task.getMaxRetry() != null ? task.getMaxRetry() : MAX_RETRY_ATTEMPTS;
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                context.setRetryCount(attempt);
                return doExecute(request, task, context);
            } catch (ConflictException e) {
                lastException = e;
                if (attempt < maxRetry) {
                    log.warn("Conflict encountered, retrying attempt {}/{}: {}", attempt + 1, maxRetry, e.getMessage());
                    Thread.sleep(100L * (attempt + 1));
                    runInstanceService.incrementRetry(context.getRunId());
                }
            }
        }

        assert lastException != null;
        throw new ConflictException("Max retry attempts exceeded: " + lastException.getMessage());
    }

    private TaskResult doExecute(TaskRequest request, Task task, ExecutionContext context) throws Exception {
        TaskHandler handler = taskRegistry.getHandler(task.getHandlerType() != null ? task.getHandlerType() : "default");

        Map<String, Object> params = task.getParameters() != null ? task.getParameters() : new java.util.HashMap<>();
        if (request.getParams() != null) {
            params.putAll(request.getParams());
        }

        RunInstanceEntity runInstance = new RunInstanceEntity();
        runInstance.setTenantId(context.getTenantId());
        runInstance.setRunId(context.getRunId());
        runInstance.setEntityId(request.getTaskId());
        runInstance.setPhase(TaskPhase.EXECUTING.getCode());
        runInstance.setProgress(0.0);
        runInstance.setConfigId(task.getFlowId());
        runInstance.setTriggerType(request.getTriggerType() != null ? request.getTriggerType() : "manual");
        runInstance.setExecutor(request.getUserId());
        runInstance.setRetryCount(context.getRetryCount());
        runInstanceService.create(runInstance);

        if (!handler.validate(params)) {
            throw new ValidationException("参数校验失败");
        }
        runInstanceService.updateProgress(context.getRunId(), TaskPhase.EXECUTING.getCode(), 0.5);

        Object resultData = handler.handle(params, context);

        runInstanceService.updateProgress(context.getRunId(), TaskPhase.COMPLETED.getCode(), 1.0);
        runInstanceService.markCompleted(context.getRunId());

        return TaskResult.builder()
                .runId(context.getRunId())
                .taskId(context.getTaskId())
                .status(TaskStatus.COMPLETED.getCode())
                .phase(TaskPhase.COMPLETED.getCode())
                .progress(1.0)
                .data(resultData)
                .startedAt(context.getStartTime())
                .completedAt(LocalDateTime.now())
                .durationMs(context.getElapsedMs())
                .build();
    }

    private void validateRequest(TaskRequest request) {
        if (request.getTaskId() == null || request.getTaskId().trim().isEmpty()) {
            throw new ValidationException("taskId", "任务ID不能为空");
        }
    }

    private void acquireResource() throws InterruptedException {
        if (!resourceSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
            throw new BusinessException(429, "系统繁忙，请稍后再试");
        }
    }

    private void releaseResource() {
        resourceSemaphore.release();
    }

    private void persistFailure(String runId, String tenantId, String taskId, Exception e) {
        try {
            RunInstanceEntity runInstance = new RunInstanceEntity();
            runInstance.setTenantId(tenantId);
            runInstance.setRunId(runId);
            runInstance.setEntityId(taskId);
            runInstance.setPhase(TaskPhase.FAILED.getCode());
            runInstance.setProgress(0.0);
            runInstance.setErrorDetail(e.getMessage());
            runInstance.setTriggerType("manual");
            runInstance.setCompletedAt(LocalDateTime.now());
            runInstanceService.create(runInstance);
        } catch (Exception ex) {
            log.error("Failed to persist failure record", ex);
        }
    }

    private void notifyStart(ExecutionContext context) {
        for (TaskLifecycleListener listener : listeners) {
            try {
                listener.onStart(context);
            } catch (Exception e) {
                    log.warn("Listener onStart failed: {}", e.getMessage());
                }
        }
    }

    private void notifySuccess(ExecutionContext context, TaskResult result) {
        for (TaskLifecycleListener listener : listeners) {
            try {
                listener.onSuccess(context, result);
            } catch (Exception e) {
                log.warn("Listener onSuccess failed: {}", e.getMessage());
            }
        }
        tenantService.updateQuotaUsage(context.getTenantId(), "task_executions", 1);
    }

    private void notifyFailure(ExecutionContext context, Throwable error) {
        for (TaskLifecycleListener listener : listeners) {
            try {
                listener.onFailure(context, error);
            } catch (Exception e) {
                log.warn("Listener onFailure failed: {}", e.getMessage());
            }
        }
    }

    private void notifyComplete(ExecutionContext context) {
        for (TaskLifecycleListener listener : listeners) {
            try {
                listener.onComplete(context);
            } catch (Exception e) {
                log.warn("Listener onComplete failed: {}", e.getMessage());
            }
        }
    }

    private void recordMetrics(ExecutionContext context) {
        Timer.builder("task.execution.duration")
                .description("Task execution duration")
                .tag("taskId", context.getTaskId())
                .register(meterRegistry)
                .record(context.getElapsedMs(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Mono<TaskResult> getStatus(String tenantId, String runId) {
        return Mono.fromCallable(() -> {
            RunInstanceEntity runInstance = runInstanceService.getById(tenantId, runId);
            return TaskResult.builder()
                    .runId(runInstance.getRunId())
                    .taskId(runInstance.getEntityId())
                    .status(runInstance.getPhase())
                    .phase(runInstance.getPhase())
                    .progress(runInstance.getProgress())
                    .errorMessage(runInstance.getErrorDetail())
                    .startedAt(runInstance.getStartedAt())
                    .completedAt(runInstance.getCompletedAt())
                    .build();
        });
    }

    @Override
    public Mono<Boolean> cancel(String tenantId, String runId) {
        return Mono.fromCallable(() -> {
            RunInstanceEntity runInstance = runInstanceService.getById(tenantId, runId);
            if (TaskPhase.COMPLETED.getCode().equals(runInstance.getPhase())
                    || TaskPhase.FAILED.getCode().equals(runInstance.getPhase())) {
                return false;
            }
            runInstanceService.markFailed(runId, runInstance.getProgress(), "Cancelled by user");
            return true;
        });
    }
}
