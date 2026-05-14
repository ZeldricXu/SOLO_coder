package com.assetmanage.service;

import com.assetmanage.config.InventoryConfigProperties;
import com.assetmanage.dto.InventoryDiffHandleRequest;
import com.assetmanage.entity.InventoryCheck;
import com.assetmanage.entity.InventoryDifference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryAsyncService {

    private final InventoryService inventoryService;
    private final InventoryConfigProperties config;

    private final Map<String, TaskStatus> taskStatusMap = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> taskLocks = new ConcurrentHashMap<>();

    public String submitAsyncProcessing(String checkId, List<String> diffIds, String operatorId) {
        if (!config.getAsync().isEnabled()) {
            log.warn("盘点异步处理已禁用，同步执行");
            processSynchronously(checkId, diffIds, operatorId);
            return "sync_" + checkId + "_" + System.currentTimeMillis();
        }

        String taskId = "task_" + checkId + "_" + System.currentTimeMillis();
        TaskStatus status = new TaskStatus();
        status.total = diffIds.size();
        status.pending = diffIds.size();
        status.checkId = checkId;
        status.operatorId = operatorId;
        status.submitTime = java.time.LocalDateTime.now();
        taskStatusMap.put(taskId, status);

        taskLocks.computeIfAbsent(taskId, k -> new ReentrantLock());

        log.info("提交异步盘点差异处理: checkId={}, taskId={}, diffCount={}", checkId, taskId, diffIds.size());

        processDifferencesAsync(taskId, diffIds);

        return taskId;
    }

    private void processSynchronously(String checkId, List<String> diffIds, String operatorId) {
        for (String diffId : diffIds) {
            try {
                InventoryDiffHandleRequest request = new InventoryDiffHandleRequest();
                request.setDiffId(diffId);
                request.setOperatorId(operatorId);
                inventoryService.handleDifference(request);
                log.info("同步处理差异成功: diffId={}", diffId);
            } catch (Exception e) {
                log.error("同步处理差异失败: diffId={}", diffId, e);
            }
        }
    }

    @Async("inventoryTaskExecutor")
    public void processDifferencesAsync(String taskId, List<String> diffIds) {
        TaskStatus status = taskStatusMap.get(taskId);
        if (status == null) {
            log.error("任务状态不存在: taskId={}", taskId);
            return;
        }

        ReentrantLock taskLock = taskLocks.get(taskId);
        if (taskLock == null) {
            log.error("任务锁不存在: taskId={}", taskId);
            return;
        }

        int maxRetries = config.getAsync().getMaxRetryCount();
        int retryDelayMs = config.getAsync().getRetryDelayMs();

        log.info("开始异步处理盘点差异: taskId={}, checkId={}, diffCount={}", 
                taskId, status.checkId, diffIds.size());

        status.startTime = java.time.LocalDateTime.now();

        for (String diffId : diffIds) {
            processSingleDiffWithRetry(diffId, status, maxRetries, retryDelayMs);
        }

        status.completed = true;
        status.endTime = java.time.LocalDateTime.now();

        long durationMs = java.time.Duration.between(status.startTime, status.endTime).toMillis();
        log.info("异步处理盘点差异完成: taskId={}, success={}, failed={}, 耗时={}ms",
                taskId, status.success.get(), status.failed.get(), durationMs);
    }

    private void processSingleDiffWithRetry(String diffId, TaskStatus status, int maxRetries, int retryDelayMs) {
        int retryCount = 0;
        boolean success = false;
        Exception lastException = null;

        while (retryCount <= maxRetries && !success) {
            try {
                InventoryDiffHandleRequest request = new InventoryDiffHandleRequest();
                request.setDiffId(diffId);
                request.setOperatorId(status.operatorId);
                inventoryService.handleDifference(request);

                success = true;
                status.success.incrementAndGet();
                status.pending--;
                status.processedDiffs.add(diffId);
                log.info("差异处理成功: diffId={}, retryCount={}, taskId={}", diffId, retryCount, status.checkId);
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                log.warn("差异处理失败，准备重试: diffId={}, retryCount={}/{}, taskId={}, error={}",
                        diffId, retryCount, maxRetries, status.checkId, e.getMessage());

                if (retryCount <= maxRetries) {
                    status.retryAttempts.incrementAndGet();
                    try {
                        Thread.sleep((long) retryDelayMs * retryCount);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("重试等待被中断: diffId={}", diffId);
                        break;
                    }
                }
            }
        }

        if (!success) {
            status.failed.incrementAndGet();
            status.pending--;
            status.failedDiffs.add(diffId);
            status.failedReasons.put(diffId, lastException != null ? lastException.getMessage() : "unknown error");
            log.error("差异处理最终失败: diffId={}, taskId={}, error={}",
                    diffId, status.checkId, lastException != null ? lastException.getMessage() : "unknown");
        }
    }

    public TaskStatus getTaskStatus(String taskId) {
        return taskStatusMap.get(taskId);
    }

    public Map<String, TaskStatus> getAllTaskStatus() {
        return new HashMap<>(taskStatusMap);
    }

    public void cancelTask(String taskId) {
        TaskStatus status = taskStatusMap.get(taskId);
        if (status != null) {
            status.cancelled = true;
            log.info("取消任务: taskId={}", taskId);
        }
    }

    public void removeCompletedTasks(int keepHours) {
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusHours(keepHours);
        taskStatusMap.entrySet().removeIf(entry -> {
            TaskStatus status = entry.getValue();
            return status.completed && status.endTime != null && status.endTime.isBefore(cutoff);
        });
    }

    public static class TaskStatus {
        public String checkId;
        public String operatorId;
        public int total;
        public int pending;
        public final AtomicInteger success = new AtomicInteger(0);
        public final AtomicInteger failed = new AtomicInteger(0);
        public final AtomicInteger retryAttempts = new AtomicInteger(0);
        public final List<String> processedDiffs = new ArrayList<>();
        public final List<String> failedDiffs = new ArrayList<>();
        public final Map<String, String> failedReasons = new HashMap<>();
        public java.time.LocalDateTime submitTime;
        public java.time.LocalDateTime startTime;
        public java.time.LocalDateTime endTime;
        public boolean completed = false;
        public boolean cancelled = false;

        public int getTotal() { return total; }
        public int getPending() { return pending; }
        public int getSuccess() { return success.get(); }
        public int getFailed() { return failed.get(); }
        public int getRetryAttempts() { return retryAttempts.get(); }
        public List<String> getProcessedDiffs() { return new ArrayList<>(processedDiffs); }
        public List<String> getFailedDiffs() { return new ArrayList<>(failedDiffs); }
        public Map<String, String> getFailedReasons() { return new HashMap<>(failedReasons); }
        public boolean isCompleted() { return completed; }
        public boolean isCancelled() { return cancelled; }
        public double getProgress() {
            if (total == 0) return 100.0;
            return (double) (getSuccess() + getFailed()) / total * 100;
        }
        public Long getDurationMs() {
            if (startTime == null) return null;
            java.time.LocalDateTime end = endTime != null ? endTime : java.time.LocalDateTime.now();
            return java.time.Duration.between(startTime, end).toMillis();
        }
    }
}
