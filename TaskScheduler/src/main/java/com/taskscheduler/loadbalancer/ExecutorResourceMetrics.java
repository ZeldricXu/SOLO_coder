package com.taskscheduler.loadbalancer;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutorResourceMetrics {

    private String executorId;
    private AtomicInteger taskCount = new AtomicInteger(0);
    private AtomicInteger cpuUsagePercent = new AtomicInteger(0);
    private AtomicInteger memoryUsagePercent = new AtomicInteger(0);
    private AtomicLong totalTaskDuration = new AtomicLong(0);
    private AtomicInteger completedTasks = new AtomicInteger(0);
    private LocalDateTime lastUpdateTime;

    private static final ConcurrentHashMap<String, ExecutorResourceMetrics> metricsCache = new ConcurrentHashMap<>();

    public static ExecutorResourceMetrics getOrCreate(String executorId) {
        return metricsCache.computeIfAbsent(executorId, id -> {
            ExecutorResourceMetrics metrics = new ExecutorResourceMetrics();
            metrics.setExecutorId(id);
            metrics.setLastUpdateTime(LocalDateTime.now());
            return metrics;
        });
    }

    public static void remove(String executorId) {
        metricsCache.remove(executorId);
    }

    public void incrementTaskCount() {
        taskCount.incrementAndGet();
        lastUpdateTime = LocalDateTime.now();
    }

    public void decrementTaskCount() {
        taskCount.updateAndGet(v -> Math.max(0, v - 1));
        lastUpdateTime = LocalDateTime.now();
    }

    public void updateCpuUsage(int percent) {
        cpuUsagePercent.set(Math.max(0, Math.min(100, percent)));
        lastUpdateTime = LocalDateTime.now();
    }

    public void updateMemoryUsage(int percent) {
        memoryUsagePercent.set(Math.max(0, Math.min(100, percent)));
        lastUpdateTime = LocalDateTime.now();
    }

    public void recordTaskCompletion(long durationSeconds) {
        completedTasks.incrementAndGet();
        totalTaskDuration.addAndGet(durationSeconds);
        decrementTaskCount();
    }

    public int getCurrentTaskCount() {
        return taskCount.get();
    }

    public int getCurrentCpuUsage() {
        return cpuUsagePercent.get();
    }

    public int getCurrentMemoryUsage() {
        return memoryUsagePercent.get();
    }

    public double getAverageTaskDuration() {
        if (completedTasks.get() == 0) {
            return 0.0;
        }
        return (double) totalTaskDuration.get() / completedTasks.get();
    }
}
