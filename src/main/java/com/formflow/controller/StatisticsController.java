package com.formflow.controller;

import com.formflow.common.ApiResponse;
import com.formflow.service.StatisticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsController.class);

    @Autowired
    private StatisticsService statisticsService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> getOverviewStatistics() {
        logger.info("查询总体统计数据");
        Map<String, Object> stats = statisticsService.getOverallStatistics();
        return ApiResponse.success(stats);
    }

    @GetMapping("/forms")
    public ApiResponse<Map<String, Object>> getFormStatistics(
            @RequestParam(required = false) String templateId) {
        logger.info("查询表单统计数据: templateId={}", templateId);
        Map<String, Object> stats = statisticsService.getFormStatistics(templateId);
        return ApiResponse.success(stats);
    }

    @GetMapping("/processes")
    public ApiResponse<Map<String, Object>> getProcessStatistics(
            @RequestParam(required = false) String processId) {
        logger.info("查询流程统计数据: processId={}", processId);
        Map<String, Object> stats = statisticsService.getProcessStatistics(processId);
        return ApiResponse.success(stats);
    }

    @GetMapping("/approvals/{approverId}")
    public ApiResponse<Map<String, Object>> getApprovalTaskStatistics(
            @PathVariable String approverId) {
        logger.info("查询审批任务统计: approverId={}", approverId);
        Map<String, Object> stats = statisticsService.getApprovalTaskStatistics(approverId);
        return ApiResponse.success(stats);
    }

    @GetMapping("/daily")
    public ApiResponse<Map<String, Object>> getDailyStatistics(
            @RequestParam(required = false) String date) {
        LocalDate queryDate = (date != null && !date.isEmpty())
                ? LocalDate.parse(date)
                : LocalDate.now();
        logger.info("查询每日统计数据: date={}", queryDate);
        Map<String, Object> stats = statisticsService.getDailyStatistics(queryDate);
        return ApiResponse.success(stats);
    }

    @GetMapping("/forms/status-distribution")
    public ApiResponse<Map<String, Long>> getFormStatusDistribution(
            @RequestParam(required = false) String templateId) {
        logger.info("查询表单状态分布: templateId={}", templateId);
        Map<String, Long> distribution = statisticsService.getFormStatusDistribution(templateId);
        return ApiResponse.success(distribution);
    }

    @GetMapping("/processes/status-distribution")
    public ApiResponse<Map<String, Long>> getProcessStatusDistribution(
            @RequestParam(required = false) String processId) {
        logger.info("查询流程状态分布: processId={}", processId);
        Map<String, Long> distribution = statisticsService.getProcessStatusDistribution(processId);
        return ApiResponse.success(distribution);
    }

    @GetMapping("/approvals/recent/{approverId}")
    public ApiResponse<List<Map<String, Object>>> getRecentApprovals(
            @PathVariable String approverId,
            @RequestParam(defaultValue = "10") int limit) {
        logger.info("查询最近审批记录: approverId={}, limit={}", approverId, limit);
        List<Map<String, Object>> records = statisticsService.getRecentApprovals(approverId, limit);
        return ApiResponse.success(records);
    }

    @GetMapping("/processes/details/{instanceId}")
    public ApiResponse<Map<String, Object>> getProcessStatusDetails(
            @PathVariable String instanceId) {
        logger.info("查询流程状态详情: instanceId={}", instanceId);
        Map<String, Object> details = statisticsService.getProcessStatusDetails(instanceId);
        return ApiResponse.success(details);
    }
}
