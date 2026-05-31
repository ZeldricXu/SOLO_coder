package com.metricplatform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.dto.ScheduledTaskDTO;
import com.metricplatform.dto.TaskExecutionResult;
import com.metricplatform.entity.SysScheduledTask;
import com.metricplatform.mapper.SysScheduledTaskMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cron.core.Trigger;
import org.springframework.cron.expression.CronExpression;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService extends ServiceImpl<SysScheduledTaskMapper, SysScheduledTask> {

    private final ApplicationContext applicationContext;
    private final Map<String, TaskHandler> taskHandlers = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();
    private final Map<String, TaskExecutionResult> taskResults = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        Map<String, TaskHandler> beans = applicationContext.getBeansOfType(TaskHandler.class);
        for (TaskHandler handler : beans.values()) {
            taskHandlers.put(handler.getTaskType().toUpperCase(), handler);
        }
        log.info("已注册 {} 个任务处理器: {}", taskHandlers.size(), taskHandlers.keySet());

        List<SysScheduledTask> activeTasks = this.list(new LambdaQueryWrapper<SysScheduledTask>()
                .in(SysScheduledTask::getStatus, "running", "paused"));
        for (SysScheduledTask task : activeTasks) {
            task.setStatus("stopped");
            this.updateById(task);
        }
    }

    @Scheduled(fixedRate = 5000)
    public void checkScheduledTasks() {
        LocalDateTime now = LocalDateTime.now();
        List<SysScheduledTask> tasks = this.list(new LambdaQueryWrapper<SysScheduledTask>()
                .eq(SysScheduledTask::getStatus, "running")
                .isNotNull(SysScheduledTask::getCronExpression)
                .and(w -> w.isNull(SysScheduledTask::getNextRunAt)
                        .or()
                        .le(SysScheduledTask::getNextRunAt, now)));

        for (SysScheduledTask task : tasks) {
            if (!runningTasks.containsKey(task.getTaskId())) {
                try {
                    executeTaskWithDependencies(task.getTaskId());
                    updateNextRunTime(task);
                } catch (Exception e) {
                    log.error("触发任务失败: {}", task.getTaskName(), e);
                }
            }
        }
    }

    @Scheduled(fixedRate = 10000)
    public void checkTimeoutTasks() {
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, Future<?>> entry : runningTasks.entrySet()) {
            String taskId = entry.getKey();
            Future<?> future = entry.getValue();

            SysScheduledTask task = this.getById(taskId);
            if (task != null && task.getLastRunAt() != null) {
                long elapsed = java.time.Duration.between(task.getLastRunAt(), now).toMillis();
                if (elapsed > task.getTimeout()) {
                    log.warn("任务执行超时，正在取消: {} (已运行 {}ms)", task.getTaskName(), elapsed);
                    future.cancel(true);
                    runningTasks.remove(taskId);

                    TaskExecutionResult result = TaskExecutionResult.builder()
                            .taskId(taskId)
                            .taskName(task.getTaskName())
                            .status("timeout")
                            .errorMessage("任务执行超时，已自动取消")
                            .startTime(task.getLastRunAt())
                            .endTime(now)
                            .durationMs(elapsed)
                            .build();
                    taskResults.put(taskId, result);
                }
            }
        }
    }

    private void updateNextRunTime(SysScheduledTask task) {
        if (task.getCronExpression() != null && !task.getCronExpression().isEmpty()) {
            try {
                CronExpression cron = new CronExpression(task.getCronExpression());
                Date next = cron.next(Date.from(LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant()));
                if (next != null) {
                    task.setNextRunAt(next.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
                    this.updateById(task);
                }
            } catch (Exception e) {
                log.error("计算下次执行时间失败: {}", task.getCronExpression(), e);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public SysScheduledTask createTask(ScheduledTaskDTO dto) {
        if (dto.getCronExpression() == null && dto.getDependencies() == null) {
            throw new IllegalArgumentException("任务必须指定Cron表达式或依赖任务");
        }

        if (dto.getCronExpression() != null) {
            try {
                new CronExpression(dto.getCronExpression());
            } catch (Exception e) {
                throw new IllegalArgumentException("无效的Cron表达式: " + dto.getCronExpression());
            }
        }

        SysScheduledTask task = new SysScheduledTask();
        task.setTaskId("task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        task.setTaskName(dto.getTaskName());
        task.setTaskType(dto.getTaskType().toUpperCase());
        task.setCronExpression(dto.getCronExpression());
        task.setDependencies(dto.getDependencies());
        task.setParameters(dto.getParameters());
        task.setStatus("stopped");
        task.setRetryCount(dto.getRetryCount());
        task.setTimeout(dto.getTimeout());

        this.save(task);
        log.info("已创建任务: {} (ID: {})", dto.getTaskName(), task.getTaskId());
        return task;
    }

    @Transactional(rollbackFor = Exception.class)
    public SysScheduledTask startTask(String taskId) {
        SysScheduledTask task = this.getById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }

        if (!"stopped".equals(task.getStatus()) && !"paused".equals(task.getStatus())) {
            throw new IllegalStateException("任务状态不允许启动: " + task.getStatus());
        }

        task.setStatus("running");
        if (task.getCronExpression() != null) {
            updateNextRunTime(task);
        }
        this.updateById(task);
        log.info("已启动任务: {} (ID: {})", task.getTaskName(), taskId);
        return task;
    }

    @Transactional(rollbackFor = Exception.class)
    public SysScheduledTask pauseTask(String taskId) {
        SysScheduledTask task = this.getById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }

        task.setStatus("paused");
        this.updateById(task);

        Future<?> future = runningTasks.get(taskId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
            runningTasks.remove(taskId);
        }

        log.info("已暂停任务: {} (ID: {})", task.getTaskName(), taskId);
        return task;
    }

    @Transactional(rollbackFor = Exception.class)
    public SysScheduledTask stopTask(String taskId) {
        SysScheduledTask task = this.getById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }

        task.setStatus("stopped");
        task.setNextRunAt(null);
        this.updateById(task);

        Future<?> future = runningTasks.get(taskId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
            runningTasks.remove(taskId);
        }

        log.info("已停止任务: {} (ID: {})", task.getTaskName(), taskId);
        return task;
    }

    @Async("scheduleExecutor")
    public CompletableFuture<TaskExecutionResult> executeTaskWithDependencies(String taskId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doExecuteTaskWithDependencies(taskId, new ConcurrentHashMap<>());
            } catch (Exception e) {
                log.error("任务执行失败: {}", taskId, e);
                return TaskExecutionResult.builder()
                        .taskId(taskId)
                        .status("failed")
                        .errorMessage(e.getMessage())
                        .build();
            }
        }, applicationContext.getBean("scheduleExecutor", Executor.class));
    }

    private TaskExecutionResult doExecuteTaskWithDependencies(String taskId, Map<String, Object> context) {
        SysScheduledTask task = this.getById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }

        if (task.getDependencies() != null && !task.getDependencies().isEmpty()) {
            for (String depTaskId : task.getDependencies()) {
                if (!taskResults.containsKey(depTaskId) ||
                        !"completed".equals(taskResults.get(depTaskId).getStatus())) {
                    throw new IllegalStateException("依赖任务未完成: " + depTaskId);
                }
                context.put("dep_" + depTaskId, taskResults.get(depTaskId).getResult());
            }
        }

        AtomicBoolean cancelled = new AtomicBoolean(false);
        CompletableFuture<TaskExecutionResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                return executeTaskInternal(task, context);
            } catch (Exception e) {
                log.error("任务内部执行失败: {}", task.getTaskName(), e);
                return TaskExecutionResult.builder()
                        .taskId(taskId)
                        .taskName(task.getTaskName())
                        .status("failed")
                        .errorMessage(e.getMessage())
                        .startTime(LocalDateTime.now())
                        .endTime(LocalDateTime.now())
                        .build();
            }
        }, applicationContext.getBean("scheduleExecutor", Executor.class));

        runningTasks.put(taskId, future);

        try {
            task.setLastRunAt(LocalDateTime.now());
            this.updateById(task);

            TaskExecutionResult result = future.get(task.getTimeout(), TimeUnit.MILLISECONDS);

            if (!"completed".equals(result.getStatus()) && task.getRetryCount() > 0) {
                for (int i = 1; i <= task.getRetryCount(); i++) {
                    log.info("任务重试 ({}/{}): {}", i, task.getRetryCount(), task.getTaskName());
                    result = executeTaskInternal(task, context);
                    result.setRetryAttempt(i);
                    if ("completed".equals(result.getStatus())) {
                        break;
                    }
                    Thread.sleep(1000 * i);
                }
            }

            taskResults.put(taskId, result);
            return result;

        } catch (TimeoutException e) {
            future.cancel(true);
            TaskExecutionResult result = TaskExecutionResult.builder()
                    .taskId(taskId)
                    .taskName(task.getTaskName())
                    .status("timeout")
                    .errorMessage("任务执行超时")
                    .startTime(task.getLastRunAt())
                    .endTime(LocalDateTime.now())
                    .durationMs(task.getTimeout())
                    .build();
            taskResults.put(taskId, result);
            return result;
        } catch (Exception e) {
            TaskExecutionResult result = TaskExecutionResult.builder()
                    .taskId(taskId)
                    .taskName(task.getTaskName())
                    .status("failed")
                    .errorMessage(e.getMessage())
                    .startTime(task.getLastRunAt())
                    .endTime(LocalDateTime.now())
                    .build();
            taskResults.put(taskId, result);
            return result;
        } finally {
            runningTasks.remove(taskId);
        }
    }

    private TaskExecutionResult executeTaskInternal(SysScheduledTask task, Map<String, Object> context) {
        LocalDateTime startTime = LocalDateTime.now();
        String taskType = task.getTaskType().toUpperCase();

        TaskHandler handler = taskHandlers.get(taskType);
        if (handler == null) {
            handler = taskHandlers.get("DEFAULT");
        }

        log.info("开始执行任务: {} (类型: {})", task.getTaskName(), taskType);
        TaskExecutionResult result = handler.execute(task, context);

        if (result.getStartTime() == null) {
            result.setStartTime(startTime);
        }
        if (result.getEndTime() == null) {
            result.setEndTime(LocalDateTime.now());
        }
        if (result.getDurationMs() == 0) {
            result.setDurationMs(java.time.Duration.between(startTime, LocalDateTime.now()).toMillis());
        }

        log.info("任务执行完成: {} (状态: {}, 耗时: {}ms)",
                task.getTaskName(), result.getStatus(), result.getDurationMs());

        return result;
    }

    public TaskExecutionResult getTaskResult(String taskId) {
        return taskResults.get(taskId);
    }

    public List<SysScheduledTask> getAllTasks() {
        return this.list();
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTask(String taskId) {
        stopTask(taskId);
        taskResults.remove(taskId);
        return this.removeById(taskId);
    }

    public Map<String, TaskHandler> getRegisteredHandlers() {
        return Collections.unmodifiableMap(taskHandlers);
    }
}
