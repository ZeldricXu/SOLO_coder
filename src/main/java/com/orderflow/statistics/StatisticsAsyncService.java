package com.orderflow.statistics;

import com.orderflow.service.StatisticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StatisticsAsyncService {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsAsyncService.class);

    private static final String TASK_PREFIX = "statistics:task:";

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private StatisticsResultCache statisticsResultCache;

    private final ConcurrentHashMap<String, CompletableFuture<StatisticsTaskResult>> pendingTasks = new ConcurrentHashMap<>();

    public StatisticsTaskResponse submitOverviewTask() {
        String taskId = generateTaskId("overview");
        logger.info("提交统计概览任务，任务ID: {}", taskId);

        StatisticsTaskInfo taskInfo = new StatisticsTaskInfo();
        taskInfo.setTaskId(taskId);
        taskInfo.setTaskType("overview");
        taskInfo.setStatus(StatisticsTaskStatus.PENDING.name());
        taskInfo.setSubmittedAt(System.currentTimeMillis());

        statisticsResultCache.saveTaskInfo(taskInfo);

        CompletableFuture<StatisticsTaskResult> future = new CompletableFuture<>();
        pendingTasks.put(taskId, future);

        asyncCalculateOverview(taskId);

        return StatisticsTaskResponse.builder()
                .taskId(taskId)
                .status(StatisticsTaskStatus.PENDING.name())
                .message("统计任务已提交，请稍后查询结果")
                .build();
    }

    public StatisticsTaskResponse submitRecentOrdersTask(int limit) {
        String taskId = generateTaskId("recent-orders");
        logger.info("提交最近订单统计任务，任务ID: {}", taskId);

        StatisticsTaskInfo taskInfo = new StatisticsTaskInfo();
        taskInfo.setTaskId(taskId);
        taskInfo.setTaskType("recent-orders");
        taskInfo.setStatus(StatisticsTaskStatus.PENDING.name());
        taskInfo.setSubmittedAt(System.currentTimeMillis());
        taskInfo.setLimit(limit);

        statisticsResultCache.saveTaskInfo(taskInfo);

        CompletableFuture<StatisticsTaskResult> future = new CompletableFuture<>();
        pendingTasks.put(taskId, future);

        asyncCalculateRecentOrders(taskId, limit);

        return StatisticsTaskResponse.builder()
                .taskId(taskId)
                .status(StatisticsTaskStatus.PENDING.name())
                .message("统计任务已提交，请稍后查询结果")
                .build();
    }

    public StatisticsTaskResponse submitDailyStatisticsTask(LocalDateTime startDate, LocalDateTime endDate) {
        String taskId = generateTaskId("daily");
        logger.info("提交每日统计任务，任务ID: {}", taskId);

        StatisticsTaskInfo taskInfo = new StatisticsTaskInfo();
        taskInfo.setTaskId(taskId);
        taskInfo.setTaskType("daily");
        taskInfo.setStatus(StatisticsTaskStatus.PENDING.name());
        taskInfo.setSubmittedAt(System.currentTimeMillis());
        taskInfo.setStartDate(startDate);
        taskInfo.setEndDate(endDate);

        statisticsResultCache.saveTaskInfo(taskInfo);

        CompletableFuture<StatisticsTaskResult> future = new CompletableFuture<>();
        pendingTasks.put(taskId, future);

        asyncCalculateDailyStatistics(taskId, startDate, endDate);

        return StatisticsTaskResponse.builder()
                .taskId(taskId)
                .status(StatisticsTaskStatus.PENDING.name())
                .message("统计任务已提交，请稍后查询结果")
                .build();
    }

    @Async("statisticsExecutor")
    public void asyncCalculateOverview(String taskId) {
        logger.info("异步计算统计概览，任务ID: {}", taskId);

        try {
            updateTaskStatus(taskId, StatisticsTaskStatus.RUNNING);

            Map<String, Object> result = statisticsService.getOrderStatistics();

            StatisticsTaskResult taskResult = new StatisticsTaskResult();
            taskResult.setTaskId(taskId);
            taskResult.setStatus(StatisticsTaskStatus.COMPLETED.name());
            taskResult.setResult(result);
            taskResult.setCompletedAt(System.currentTimeMillis());

            statisticsResultCache.saveTaskResult(taskResult);
            updateTaskStatus(taskId, StatisticsTaskStatus.COMPLETED);

            CompletableFuture<StatisticsTaskResult> future = pendingTasks.remove(taskId);
            if (future != null) {
                future.complete(taskResult);
            }

            logger.info("统计概览计算完成，任务ID: {}", taskId);
        } catch (Exception e) {
            logger.error("统计概览计算失败，任务ID: {}", taskId, e);
            updateTaskStatus(taskId, StatisticsTaskStatus.FAILED);

            StatisticsTaskResult taskResult = new StatisticsTaskResult();
            taskResult.setTaskId(taskId);
            taskResult.setStatus(StatisticsTaskStatus.FAILED.name());
            taskResult.setErrorMessage(e.getMessage());
            taskResult.setCompletedAt(System.currentTimeMillis());
            statisticsResultCache.saveTaskResult(taskResult);

            CompletableFuture<StatisticsTaskResult> future = pendingTasks.remove(taskId);
            if (future != null) {
                future.complete(taskResult);
            }
        }
    }

    @Async("statisticsExecutor")
    public void asyncCalculateRecentOrders(String taskId, int limit) {
        logger.info("异步计算最近订单，任务ID: {}", taskId);

        try {
            updateTaskStatus(taskId, StatisticsTaskStatus.RUNNING);

            List<Map<String, Object>> result = statisticsService.getRecentOrders(limit);

            StatisticsTaskResult taskResult = new StatisticsTaskResult();
            taskResult.setTaskId(taskId);
            taskResult.setStatus(StatisticsTaskStatus.COMPLETED.name());
            taskResult.setResult(result);
            taskResult.setCompletedAt(System.currentTimeMillis());

            statisticsResultCache.saveTaskResult(taskResult);
            updateTaskStatus(taskId, StatisticsTaskStatus.COMPLETED);

            CompletableFuture<StatisticsTaskResult> future = pendingTasks.remove(taskId);
            if (future != null) {
                future.complete(taskResult);
            }

            logger.info("最近订单统计完成，任务ID: {}", taskId);
        } catch (Exception e) {
            logger.error("最近订单统计失败，任务ID: {}", taskId, e);
            handleTaskFailure(taskId, e);
        }
    }

    @Async("statisticsExecutor")
    public void asyncCalculateDailyStatistics(String taskId, LocalDateTime startDate, LocalDateTime endDate) {
        logger.info("异步计算每日统计，任务ID: {}", taskId);

        try {
            updateTaskStatus(taskId, StatisticsTaskStatus.RUNNING);

            Map<String, Object> result = statisticsService.getDailyStatistics(startDate, endDate);

            StatisticsTaskResult taskResult = new StatisticsTaskResult();
            taskResult.setTaskId(taskId);
            taskResult.setStatus(StatisticsTaskStatus.COMPLETED.name());
            taskResult.setResult(result);
            taskResult.setCompletedAt(System.currentTimeMillis());

            statisticsResultCache.saveTaskResult(taskResult);
            updateTaskStatus(taskId, StatisticsTaskStatus.COMPLETED);

            CompletableFuture<StatisticsTaskResult> future = pendingTasks.remove(taskId);
            if (future != null) {
                future.complete(taskResult);
            }

            logger.info("每日统计完成，任务ID: {}", taskId);
        } catch (Exception e) {
            logger.error("每日统计失败，任务ID: {}", taskId, e);
            handleTaskFailure(taskId, e);
        }
    }

    private void handleTaskFailure(String taskId, Exception e) {
        updateTaskStatus(taskId, StatisticsTaskStatus.FAILED);

        StatisticsTaskResult taskResult = new StatisticsTaskResult();
        taskResult.setTaskId(taskId);
        taskResult.setStatus(StatisticsTaskStatus.FAILED.name());
        taskResult.setErrorMessage(e.getMessage());
        taskResult.setCompletedAt(System.currentTimeMillis());
        statisticsResultCache.saveTaskResult(taskResult);

        CompletableFuture<StatisticsTaskResult> future = pendingTasks.remove(taskId);
        if (future != null) {
            future.complete(taskResult);
        }
    }

    private void updateTaskStatus(String taskId, StatisticsTaskStatus status) {
        StatisticsTaskInfo taskInfo = statisticsResultCache.getTaskInfo(taskId);
        if (taskInfo != null) {
            taskInfo.setStatus(status.name());
            statisticsResultCache.saveTaskInfo(taskInfo);
        }
    }

    public StatisticsTaskResult getTaskResult(String taskId) {
        return statisticsResultCache.getTaskResult(taskId);
    }

    public StatisticsTaskInfo getTaskInfo(String taskId) {
        return statisticsResultCache.getTaskInfo(taskId);
    }

    private String generateTaskId(String type) {
        return type + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
