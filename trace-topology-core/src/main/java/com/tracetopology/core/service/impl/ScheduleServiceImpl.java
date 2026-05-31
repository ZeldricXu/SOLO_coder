package com.tracetopology.core.service.impl;

import com.tracetopology.api.service.ScheduleService;
import com.tracetopology.common.exception.BaseException;
import com.tracetopology.common.result.PageResult;
import com.tracetopology.common.utils.IdGenerator;
import com.tracetopology.core.validation.ParamValidator;
import com.tracetopology.domain.schedule.TaskExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class ScheduleServiceImpl implements ScheduleService {

    private final Map<String, ScheduledTask> tasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);

    @Override
    public String scheduleTask(String taskType, Map<String, Object> params, Instant runAt) {
        ParamValidator.validateNotBlank(taskType, "taskType");
        ParamValidator.validateNotNull(params, "params");
        ParamValidator.validateNotNull(runAt, "runAt");

        String taskId = IdGenerator.generateId("task");
        long delay = Math.max(0, runAt.toEpochMilli() - System.currentTimeMillis());

        ScheduledTask task = new ScheduledTask(taskId, taskType, params, runAt, null, null);
        tasks.put(taskId, task);

        scheduler.schedule(() -> executeTask(task), delay, TimeUnit.MILLISECONDS);

        log.info("任务已调度: taskId={}, taskType={}, runAt={}", taskId, taskType, runAt);
        return taskId;
    }

    @Override
    public String scheduleRecurringTask(String taskType, Map<String, Object> params, Duration interval) {
        ParamValidator.validateNotBlank(taskType, "taskType");
        ParamValidator.validateNotNull(params, "params");
        ParamValidator.validateNotNull(interval, "interval");
        ParamValidator.validatePositive(interval.toMillis(), "interval");

        String taskId = IdGenerator.generateId("task");
        Instant firstRunAt = Instant.now().plus(interval);

        ScheduledTask task = new ScheduledTask(taskId, taskType, params, firstRunAt, interval, null);
        task.setRecurring(true);
        tasks.put(taskId, task);

        scheduler.scheduleAtFixedRate(() -> executeTask(task),
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);

        log.info("周期性任务已调度: taskId={}, taskType={}, interval={}ms",
                taskId, taskType, interval.toMillis());
        return taskId;
    }

    @Override
    public String scheduleCronTask(String taskType, Map<String, Object> params, String cronExpression) {
        ParamValidator.validateNotBlank(taskType, "taskType");
        ParamValidator.validateNotNull(params, "params");
        ParamValidator.validateNotBlank(cronExpression, "cronExpression");

        validateCronExpression(cronExpression);

        String taskId = IdGenerator.generateId("task");
        Instant firstRunAt = calculateNextCronRun(cronExpression);

        ScheduledTask task = new ScheduledTask(taskId, taskType, params, firstRunAt, null, cronExpression);
        task.setCronExpression(cronExpression);
        tasks.put(taskId, task);

        scheduleNextCronRun(task, cronExpression);

        log.info("Cron任务已调度: taskId={}, taskType={}, cron={}", taskId, taskType, cronExpression);
        return taskId;
    }

    @Override
    public boolean cancelTask(String taskId) {
        ParamValidator.validateNotBlank(taskId, "taskId");

        ScheduledTask task = tasks.get(taskId);
        if (task == null) {
            return false;
        }

        task.setStatus("cancelled");
        task.setCancelled(true);
        log.info("任务已取消: taskId={}", taskId);
        return true;
    }

    @Override
    public Map<String, Object> getTaskStatus(String taskId) {
        ParamValidator.validateNotBlank(taskId, "taskId");

        ScheduledTask task = tasks.get(taskId);
        if (task == null) {
            throw new BaseException("TASK_NOT_FOUND", "任务不存在: " + taskId);
        }

        Map<String, Object> status = new HashMap<>();
        status.put("taskId", task.getTaskId());
        status.put("taskType", task.getTaskType());
        status.put("status", task.getStatus());
        status.put("progress", task.getProgress());
        status.put("message", task.getMessage());
        status.put("nextRunAt", task.getNextRunAt());
        status.put("lastRunAt", task.getLastRunAt());
        status.put("result", task.getResult());
        status.put("error", task.getError());
        status.put("recurring", task.isRecurring());
        status.put("cancelled", task.isCancelled());
        return status;
    }

    @Override
    public List<Map<String, Object>> listTasks(String status, int pageNum, int pageSize) {
        ParamValidator.validatePositive(pageNum, "pageNum");
        ParamValidator.validatePositive(pageSize, "pageSize");

        List<ScheduledTask> filtered = new ArrayList<>();
        for (ScheduledTask task : tasks.values()) {
            if (status == null || status.equals(task.getStatus())) {
                filtered.add(task);
            }
        }

        int start = (pageNum - 1) * pageSize;
        int end = Math.min(start + pageSize, filtered.size());

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = start; i < end; i++) {
            result.add(getTaskStatus(filtered.get(i).getTaskId()));
        }

        PageResult<Map<String, Object>> pageResult = PageResult.of(result, filtered.size(), pageNum, pageSize);
        return pageResult.getRecords();
    }

    @Override
    public void trackTaskProgress(String taskId, double progress, String message) {
        ParamValidator.validateNotBlank(taskId, "taskId");
        ParamValidator.validateRange(progress, 0.0, 1.0, "progress");

        ScheduledTask task = tasks.get(taskId);
        if (task != null) {
            task.setProgress(progress);
            task.setMessage(message);
            task.setStatus("running");
            log.debug("任务进度更新: taskId={}, progress={}%, message={}",
                    taskId, (int) (progress * 100), message);
        }
    }

    @Override
    public void completeTask(String taskId, Map<String, Object> result) {
        ParamValidator.validateNotBlank(taskId, "taskId");

        ScheduledTask task = tasks.get(taskId);
        if (task != null) {
            task.setStatus("completed");
            task.setProgress(1.0);
            task.setResult(result);
            task.setLastRunAt(Instant.now());
            log.info("任务已完成: taskId={}", taskId);
        }
    }

    @Override
    public void failTask(String taskId, String error) {
        ParamValidator.validateNotBlank(taskId, "taskId");
        ParamValidator.validateNotBlank(error, "error");

        ScheduledTask task = tasks.get(taskId);
        if (task != null) {
            task.setStatus("failed");
            task.setError(error);
            task.setLastRunAt(Instant.now());
            log.error("任务执行失败: taskId={}, error={}", taskId, error);
        }
    }

    private void executeTask(ScheduledTask task) {
        if (task.isCancelled()) {
            log.info("任务已取消，跳过执行: taskId={}", task.getTaskId());
            return;
        }

        task.setStatus("running");
        task.setLastRunAt(Instant.now());
        log.info("开始执行任务: taskId={}, type={}", task.getTaskId(), task.getTaskType());

        try {
            Map<String, Object> result = executeTaskLogic(task);
            completeTask(task.getTaskId(), result);
        } catch (Exception e) {
            failTask(task.getTaskId(), e.getMessage());
        }

        if (task.getCronExpression() != null && !task.isCancelled()) {
            scheduleNextCronRun(task, task.getCronExpression());
        }
    }

    protected Map<String, Object> executeTaskLogic(ScheduledTask task) {
        Map<String, Object> result = new HashMap<>();
        result.put("executed", true);
        result.put("taskType", task.getTaskType());
        result.put("params", task.getParams());
        result.put("executedAt", Instant.now());
        return result;
    }

    private void validateCronExpression(String cronExpression) {
        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length < 5 || parts.length > 6) {
            throw new IllegalArgumentException("Cron表达式格式错误: " + cronExpression);
        }

        String[] regexPatterns = {
                "^(\\*|([0-5]?\\d)(,[0-5]?\\d)*|([0-5]?\\d)-([0-5]?\\d)|(\\*|([0-5]?\\d))/\\d+)$",
                "^(\\*|([0-5]?\\d)(,[0-5]?\\d)*|([0-5]?\\d)-([0-5]?\\d)|(\\*|([0-5]?\\d))/\\d+)$",
                "^(\\*|([01]?\\d|2[0-3])(,([01]?\\d|2[0-3]))*|([01]?\\d|2[0-3])-([01]?\\d|2[0-3])|(\\*|([01]?\\d|2[0-3]))/\\d+)$",
                "^(\\*|([1-9]|[12]\\d|3[01])(,([1-9]|[12]\\d|3[01]))*|([1-9]|[12]\\d|3[01])-([1-9]|[12]\\d|3[01])|(\\*|([1-9]|[12]\\d|3[01]))/\\d+)$",
                "^(\\*|([1-9]|1[0-2])(,([1-9]|1[0-2]))*|([1-9]|1[0-2])-([1-9]|1[0-2])|(\\*|([1-9]|1[0-2]))/\\d+|JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)$",
                "^(\\*|[0-6](,[0-6])*|[0-6]-[0-6]|SUN|MON|TUE|WED|THU|FRI|SAT)$"
        };

        for (int i = 0; i < Math.min(parts.length, regexPatterns.length); i++) {
            if (!Pattern.matches(regexPatterns[i], parts[i])) {
                throw new IllegalArgumentException(
                        String.format("Cron表达式第%d部分格式错误: %s", i + 1, parts[i]));
            }
        }
    }

    private Instant calculateNextCronRun(String cronExpression) {
        return Instant.now().plus(Duration.ofMinutes(1));
    }

    private void scheduleNextCronRun(ScheduledTask task, String cronExpression) {
        Instant nextRun = calculateNextCronRun(cronExpression);
        task.setNextRunAt(nextRun);
        long delay = Math.max(0, nextRun.toEpochMilli() - System.currentTimeMillis());
        scheduler.schedule(() -> executeTask(task), delay, TimeUnit.MILLISECONDS);
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    static class ScheduledTask {
        private final String taskId;
        private final String taskType;
        private final Map<String, Object> params;
        private Instant nextRunAt;
        private final Duration interval;
        private String cronExpression;
        private String status = "pending";
        private double progress = 0.0;
        private String message;
        private Instant lastRunAt;
        private Map<String, Object> result;
        private String error;
        private boolean recurring = false;
        private boolean cancelled = false;
    }

    @Override
    public TaskExecution executeTask(String taskType, Map<String, Object> params, int timeoutSeconds) {
        ParamValidator.validateNotBlank(taskType, "taskType");
        ParamValidator.validateNotNull(params, "params");
        ParamValidator.validatePositive(timeoutSeconds, "timeoutSeconds");

        String executionId = IdGenerator.generateId("exec");
        TaskExecution execution = TaskExecution.builder()
                .executionId(executionId)
                .taskId(IdGenerator.generateId("task"))
                .taskType(taskType)
                .phase("executing")
                .progress(0.0)
                .startedAt(Instant.now())
                .params(params)
                .timeoutSeconds(timeoutSeconds)
                .build();

        try {
            log.info("执行任务: executionId={}, taskType={}", executionId, taskType);
            Map<String, Object> result = Map.of(
                    "executed", true,
                    "taskType", taskType,
                    "executedAt", Instant.now().toString()
            );
            execution.setPhase("completed");
            execution.setProgress(1.0);
            execution.setCompletedAt(Instant.now());
            execution.setResult(result);
            completeTask(execution.getTaskId(), result);
        } catch (Exception e) {
            execution.setPhase("failed");
            execution.setErrorDetail(e.getMessage());
            execution.setCompletedAt(Instant.now());
            failTask(execution.getTaskId(), e.getMessage());
        }

        return execution;
    }

    @Override
    public TaskExecution scheduleTask(String taskId, String taskType, String cronExpression, Map<String, Object> params) {
        ParamValidator.validateNotBlank(taskId, "taskId");
        ParamValidator.validateNotBlank(taskType, "taskType");
        ParamValidator.validateNotBlank(cronExpression, "cronExpression");

        String executionId = IdGenerator.generateId("exec");
        TaskExecution execution = TaskExecution.builder()
                .executionId(executionId)
                .taskId(taskId)
                .taskType(taskType)
                .phase("scheduled")
                .progress(0.0)
                .startedAt(Instant.now())
                .params(params)
                .build();

        scheduleCronTask(taskType, params, cronExpression);

        log.info("任务已调度: executionId={}, taskId={}, cron={}", executionId, taskId, cronExpression);
        return execution;
    }

    @Override
    public TaskExecution getTaskExecution(String executionId) {
        ParamValidator.validateNotBlank(executionId, "executionId");

        Map<String, Object> status = getTaskStatus(executionId);
        return TaskExecution.builder()
                .executionId(executionId)
                .taskId((String) status.get("taskId"))
                .taskType((String) status.get("taskType"))
                .phase((String) status.get("status"))
                .progress((Double) status.getOrDefault("progress", 0.0))
                .result((Map<String, Object>) status.get("result"))
                .errorDetail((String) status.get("error"))
                .build();
    }

    @Override
    public List<TaskExecution> listTaskExecutions(String taskType, int pageNum, int pageSize) {
        List<Map<String, Object>> taskList = listTasks(null, pageNum, pageSize);
        return taskList.stream()
                .filter(t -> taskType == null || taskType.equals(t.get("taskType")))
                .map(t -> TaskExecution.builder()
                        .executionId((String) t.get("taskId"))
                        .taskId((String) t.get("taskId"))
                        .taskType((String) t.get("taskType"))
                        .phase((String) t.get("status"))
                        .progress((Double) t.getOrDefault("progress", 0.0))
                        .build())
                .toList();
    }

    @Override
    public List<TaskExecution> getRunningTasks() {
        return listTaskExecutions(null, 1, 100).stream()
                .filter(TaskExecution::isRunning)
                .toList();
    }

    @Override
    public int recoverFailedTasks() {
        List<TaskExecution> failed = listTaskExecutions(null, 1, 100).stream()
                .filter(TaskExecution::isFailed)
                .toList();

        log.info("恢复失败任务: count={}", failed.size());
        for (TaskExecution exec : failed) {
            String newTaskId = scheduleTask(exec.getTaskType(), exec.getParams(), Instant.now().plusSeconds(10));
            log.debug("重新调度任务: oldTaskId={}, newTaskId={}", exec.getTaskId(), newTaskId);
        }

        return failed.size();
    }
}
