package com.orderflow.controller;

import com.orderflow.common.Result;
import com.orderflow.service.StatisticsService;
import com.orderflow.statistics.StatisticsAsyncService;
import com.orderflow.statistics.StatisticsTaskInfo;
import com.orderflow.statistics.StatisticsTaskResponse;
import com.orderflow.statistics.StatisticsTaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsController.class);

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private StatisticsAsyncService statisticsAsyncService;

    @GetMapping("/overview")
    public Result<Map<String, Object>> getOverview() {
        logger.info("获取订单统计概览（同步）");
        Map<String, Object> statistics = statisticsService.getOrderStatistics();
        return Result.success(statistics);
    }

    @PostMapping("/overview-async")
    public Result<StatisticsTaskResponse> submitOverviewTaskAsync() {
        logger.info("提交统计概览任务（异步）");
        StatisticsTaskResponse response = statisticsAsyncService.submitOverviewTask();
        return Result.success(response);
    }

    @GetMapping("/recent-orders")
    public Result<List<Map<String, Object>>> getRecentOrders(
            @RequestParam(defaultValue = "20") Integer limit) {
        logger.info("获取最近订单（同步），数量: {}", limit);
        List<Map<String, Object>> orders = statisticsService.getRecentOrders(limit);
        return Result.success(orders);
    }

    @PostMapping("/recent-orders-async")
    public Result<StatisticsTaskResponse> submitRecentOrdersTaskAsync(
            @RequestParam(defaultValue = "20") Integer limit) {
        logger.info("提交最近订单统计任务（异步），数量: {}", limit);
        StatisticsTaskResponse response = statisticsAsyncService.submitRecentOrdersTask(limit);
        return Result.success(response);
    }

    @GetMapping("/daily")
    public Result<Map<String, Object>> getDailyStatistics(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startDate,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endDate) {
        logger.info("获取每日统计（同步），开始日期: {}, 结束日期: {}", startDate, endDate);
        Map<String, Object> statistics = statisticsService.getDailyStatistics(startDate, endDate);
        return Result.success(statistics);
    }

    @PostMapping("/daily-async")
    public Result<StatisticsTaskResponse> submitDailyStatisticsTaskAsync(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startDate,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endDate) {
        logger.info("提交每日统计任务（异步），开始日期: {}, 结束日期: {}", startDate, endDate);
        StatisticsTaskResponse response = statisticsAsyncService.submitDailyStatisticsTask(startDate, endDate);
        return Result.success(response);
    }

    @GetMapping("/task/{taskId}")
    public Result<Map<String, Object>> getTaskStatus(@PathVariable String taskId) {
        logger.info("查询统计任务状态，任务ID: {}", taskId);

        StatisticsTaskInfo taskInfo = statisticsAsyncService.getTaskInfo(taskId);
        if (taskInfo == null) {
            return Result.error("任务不存在: " + taskId);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("taskId", taskInfo.getTaskId());
        response.put("taskType", taskInfo.getTaskType());
        response.put("status", taskInfo.getStatus());
        response.put("submittedAt", taskInfo.getSubmittedAt());

        return Result.success(response);
    }

    @GetMapping("/task/{taskId}/result")
    public Result<Map<String, Object>> getTaskResult(@PathVariable String taskId) {
        logger.info("查询统计任务结果，任务ID: {}", taskId);

        StatisticsTaskResult taskResult = statisticsAsyncService.getTaskResult(taskId);
        if (taskResult == null) {
            return Result.error("任务结果不存在或尚未完成: " + taskId);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("taskId", taskResult.getTaskId());
        response.put("status", taskResult.getStatus());
        response.put("result", taskResult.getResult());
        response.put("errorMessage", taskResult.getErrorMessage());
        response.put("completedAt", taskResult.getCompletedAt());

        return Result.success(response);
    }
}
