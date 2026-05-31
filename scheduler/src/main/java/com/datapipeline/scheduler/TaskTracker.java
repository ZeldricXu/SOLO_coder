package com.datapipeline.scheduler;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class TaskTracker {

    private final Map<String, TaskExecution> executions = new ConcurrentHashMap<>();
    private final Map<String, List<TaskExecution>> executionsByTask = new ConcurrentHashMap<>();
    private final int maxHistoryPerTask;

    public TaskTracker() {
        this(100);
    }

    public TaskTracker(int maxHistoryPerTask) {
        this.maxHistoryPerTask = maxHistoryPerTask;
    }

    public void recordExecution(ScheduledTask task) {
        TaskExecution execution = TaskExecution.builder()
                .executionId(UUID.randomUUID().toString())
                .taskId(task.getTaskId())
                .taskName(task.getName())
                .status(task.getStatus())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .durationMs(task.getDurationMs())
                .lastError(task.getLastError())
                .metadata(task.getMetadata())
                .build();

        executions.put(execution.getExecutionId(), execution);

        List<TaskExecution> history = executionsByTask.computeIfAbsent(
                task.getTaskId(), k -> new ArrayList<>());
        synchronized (history) {
            history.add(execution);
            while (history.size() > maxHistoryPerTask) {
                history.remove(0);
            }
        }

        log.debug("Task execution recorded: taskId={}, executionId={}, status={}",
                task.getTaskId(), execution.getExecutionId(), task.getStatus());
    }

    public List<TaskExecution> getExecutionHistory(String taskId) {
        List<TaskExecution> history = executionsByTask.get(taskId);
        return history != null ? new ArrayList<>(history) : Collections.emptyList();
    }

    public TaskStats getTaskStats(String taskId) {
        List<TaskExecution> history = getExecutionHistory(taskId);
        if (history.isEmpty()) {
            return TaskStats.EMPTY;
        }

        long totalDuration = 0;
        long maxDuration = 0;
        long minDuration = Long.MAX_VALUE;
        int successCount = 0;
        int failureCount = 0;

        for (TaskExecution exec : history) {
            long duration = exec.getDurationMs();
            totalDuration += duration;
            maxDuration = Math.max(maxDuration, duration);
            minDuration = Math.min(minDuration, duration);

            if (exec.getStatus() == ScheduledTask.Status.COMPLETED) {
                successCount++;
            } else {
                failureCount++;
            }
        }

        return TaskStats.builder()
                .taskId(taskId)
                .totalExecutions(history.size())
                .successCount(successCount)
                .failureCount(failureCount)
                .averageDurationMs(totalDuration / history.size())
                .maxDurationMs(maxDuration)
                .minDurationMs(minDuration == Long.MAX_VALUE ? 0 : minDuration)
                .successRate((double) successCount / history.size())
                .lastExecutionAt(history.get(history.size() - 1).getCompletedAt())
                .build();
    }

    public Map<String, TaskStats> getAllTaskStats() {
        Map<String, TaskStats> stats = new HashMap<>();
        for (String taskId : executionsByTask.keySet()) {
            stats.put(taskId, getTaskStats(taskId));
        }
        return stats;
    }

    public void clearHistory(String taskId) {
        List<TaskExecution> history = executionsByTask.remove(taskId);
        if (history != null) {
            for (TaskExecution exec : history) {
                executions.remove(exec.getExecutionId());
            }
        }
    }

}
