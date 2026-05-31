package com.taskplatform.core;

import com.taskplatform.common.enums.TaskStatus;
import com.taskplatform.common.event.ApplicationEvent;
import com.taskplatform.common.event.EventPublisher;
import com.taskplatform.common.exception.BusinessException;
import com.taskplatform.common.exception.ExceptionFactory;
import com.taskplatform.common.exception.TimeoutException;
import com.taskplatform.common.exception.ValidationException;
import com.taskplatform.common.util.CollectionUtils;
import com.taskplatform.common.util.ContextHolder;
import com.taskplatform.common.util.IdGenerator;
import com.taskplatform.common.util.JsonUtil;
import com.taskplatform.config.ConfigService;
import com.taskplatform.persistence.entity.Task;
import com.taskplatform.persistence.entity.TaskRun;
import com.taskplatform.persistence.mapper.TaskMapper;
import com.taskplatform.persistence.mapper.TaskRunMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutorService {

    private static final int DEFAULT_SEMAPHORE_PERMITS = 100;
    private static final int DEFAULT_TASK_TIMEOUT_MS = 300000;
    private static final int EVENT_PAYLOAD_CAPACITY = 4;
    private static final int METRICS_CAPACITY = 6;

    private final TaskMapper taskMapper;
    private final TaskRunMapper taskRunMapper;
    private final ConfigService configService;
    private final EventPublisher eventPublisher;
    private final List<TaskHandler> taskHandlers;
    private final MeterRegistry meterRegistry;

    private final Semaphore resourceSemaphore = new Semaphore(DEFAULT_SEMAPHORE_PERMITS);
    private final AtomicLong taskCounter = new AtomicLong(0);
    private final AtomicLong errorCounter = new AtomicLong(0);

    private Map<String, TaskHandler> handlerRegistry;
    private Timer.Builder successTimerBuilder;
    private Timer.Builder failureTimerBuilder;

    @PostConstruct
    public void init() {
        handlerRegistry = CollectionUtils.toMapByKey(taskHandlers, TaskHandler::getTaskType);
        successTimerBuilder = Timer.builder("task.execution.duration")
                .tag("status", "success");
        failureTimerBuilder = Timer.builder("task.execution.duration")
                .tag("status", "failure");
    }

    public Task submitTask(Task task) {
        task.setTaskId(IdGenerator.generateTaskId());
        task.setStatus(TaskStatus.QUEUED);
        LocalDateTime now = LocalDateTime.now();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);

        publishEvent("task.created", task);
        return task;
    }

    public Object executeTask(String taskId) {
        Task task = taskMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Task>()
                        .eq(Task::getTaskId, taskId)
        );
        if (task == null) {
            throw ExceptionFactory.taskNotFound(taskId);
        }

        try (TaskContext context = new TaskContext(task)) {
            ContextHolder.set(ContextHolder.RequestContext.builder()
                    .traceId(context.getTraceId())
                    .requestTime(LocalDateTime.now())
                    .build());

            return executeWithContext(context);
        } catch (TimeoutException | ValidationException e) {
            throw e;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Task execution failed: {}", taskId, e);
            throw ExceptionFactory.executionError(e.getMessage(), e);
        }
    }

    private Object executeWithContext(TaskContext context) throws Exception {
        Task task = context.getTask();
        Timer.Sample sample = Timer.start(meterRegistry);
        TaskRun taskRun = createTaskRun(task);
        context.setTaskRun(taskRun);

        try {
            updateTaskStatus(task, TaskStatus.RUNNING);
            taskRun.setPhase("validating");
            taskRun.setStartedAt(LocalDateTime.now());

            acquireResource(context);

            taskRun.setPhase("processing");
            Object result = processCore(context);

            taskRun.setProgress(1.0);
            taskRun.setStatus("completed");
            taskRun.setCompletedAt(LocalDateTime.now());
            context.setResult(result);
            context.setCompleted(true);

            persistResult(context, result);
            updateTaskStatus(task, TaskStatus.COMPLETED);

            publishEvent("task.completed", task);
            taskCounter.incrementAndGet();

            return result;

        } catch (java.util.concurrent.TimeoutException | TimeoutException e) {
            handleTimeout(context, e);
            throw new TimeoutException("Task execution timed out");
        } catch (ValidationException e) {
            handleError(context, e, "validation_failed");
            throw e;
        } catch (Exception e) {
            handleError(context, e, "execution_failed");
            throw e;
        } finally {
            taskRunMapper.updateById(taskRun);
            recordTimer(context, sample);
            recordMetrics(context);
        }
    }

    private void recordTimer(TaskContext context, Timer.Sample sample) {
        Timer.Builder builder = context.isCompleted() ? successTimerBuilder : failureTimerBuilder;
        sample.stop(builder
                .tag("taskType", context.getTask().getType())
                .register(meterRegistry));
    }

    private void acquireResource(TaskContext context) throws InterruptedException, TimeoutException {
        context.setResourceSemaphore(resourceSemaphore);

        long remaining = context.getRemainingTimeMs();
        if (!resourceSemaphore.tryAcquire(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw ExceptionFactory.timeout("resource_acquisition", context.getTimeoutMs());
        }
        context.setResourceAcquired(true);
    }

    private Object processCore(TaskContext context) throws Exception {
        Task task = context.getTask();
        String taskType = task.getType();

        TaskHandler handler = handlerRegistry.get(taskType);
        if (handler == null) {
            handler = handlerRegistry.get("default");
        }
        if (handler == null) {
            throw new BusinessException(400, "NO_HANDLER",
                    "No handler found for task type: " + taskType);
        }

        return handler.execute(context);
    }

    private void persistResult(TaskContext context, Object result) {
        Task task = context.getTask();
        task.setResultData(JsonUtil.toJson(result));
        task.setCompletedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private void handleTimeout(TaskContext context, Exception e) {
        Task task = context.getTask();
        TaskRun taskRun = context.getTaskRun();

        updateTaskStatus(task, TaskStatus.TIMEOUT);
        taskRun.setStatus("timeout");
        taskRun.setErrorDetail(e.getMessage());
        taskRun.setCompletedAt(LocalDateTime.now());
        context.setError(e);

        publishEvent("task.timeout", task);
        errorCounter.incrementAndGet();
    }

    private void handleError(TaskContext context, Exception e, String phase) {
        Task task = context.getTask();
        TaskRun taskRun = context.getTaskRun();

        int currentRetry = task.getRetryCount();
        int maxRetries = task.getMaxRetries() != null ? task.getMaxRetries() : 3;

        if (currentRetry < maxRetries) {
            task.setRetryCount(currentRetry + 1);
            updateTaskStatus(task, TaskStatus.PENDING);
            taskRun.setStatus("retry");
            publishEvent("task.retry", task);
        } else {
            updateTaskStatus(task, TaskStatus.FAILED);
            taskRun.setStatus("failed");
            taskRun.setErrorDetail(e.getMessage());
            taskRun.setCompletedAt(LocalDateTime.now());
            context.setError(e);
            publishEvent("task.failed", task);
            errorCounter.incrementAndGet();
        }
    }

    private TaskRun createTaskRun(Task task) {
        TaskRun run = new TaskRun();
        run.setRunId(IdGenerator.generateRunId());
        run.setTaskId(task.getTaskId());
        run.setPhase("initializing");
        run.setProgress(0.0);
        run.setStatus("running");
        run.setStartedAt(LocalDateTime.now());
        taskRunMapper.insert(run);
        return run;
    }

    private void updateTaskStatus(Task task, TaskStatus status) {
        task.setStatus(status);
        task.setUpdatedAt(LocalDateTime.now());
        if (status == TaskStatus.RUNNING) {
            task.setStartedAt(LocalDateTime.now());
        }
        taskMapper.updateById(task);
    }

    private void recordMetrics(TaskContext context) {
        meterRegistry.counter("task.execution.total",
                "taskType", context.getTask().getType()).increment();
    }

    private void publishEvent(String type, Task task) {
        Map<String, Object> payload = CollectionUtils.newHashMap(EVENT_PAYLOAD_CAPACITY);
        payload.put("taskId", task.getTaskId());
        payload.put("type", task.getType());
        payload.put("status", task.getStatus());
        eventPublisher.publish(ApplicationEvent.of(type, payload));
    }

    public long getCompletedTaskCount() {
        return taskCounter.get();
    }

    public long getErrorCount() {
        return errorCounter.get();
    }

    public int getAvailablePermits() {
        return resourceSemaphore.availablePermits();
    }
}
