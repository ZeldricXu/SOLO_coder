package com.mobilestore.controller;

import com.mobilestore.common.ApiResponse;
import com.mobilestore.dto.StatisticsRequest;
import com.mobilestore.entity.Statistics;
import com.mobilestore.service.StatisticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/statistics")
@CrossOrigin(origins = "*")
public class StatisticsController {

    @Autowired
    private StatisticsService statisticsService;

    @GetMapping
    public ApiResponse<Map<String, Object>> getStatistics(StatisticsRequest request) {
        Map<String, Object> result = statisticsService.getStatistics(request);
        return ApiResponse.success(result);
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> getSummaryStatistics(@RequestParam String appId) {
        Map<String, Object> summary = statisticsService.getSummaryStatistics(appId);
        return ApiResponse.success(summary);
    }

    @GetMapping("/summary/wait")
    public ApiResponse<Map<String, Object>> waitForSummary(
            @RequestParam String appId,
            @RequestParam(defaultValue = "10") int maxWaitSeconds) {
        Map<String, Object> result = statisticsService.waitForSummary(appId, maxWaitSeconds);
        return ApiResponse.success(result);
    }

    @GetMapping("/chart")
    public ApiResponse<Map<String, Object>> getChartData(StatisticsRequest request) {
        Map<String, Object> chartData = statisticsService.getChartData(request);
        return ApiResponse.success(chartData);
    }

    @GetMapping("/chart/wait")
    public ApiResponse<Map<String, Object>> waitForChart(
            @RequestParam String appId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "10") int maxWaitSeconds) {
        Map<String, Object> result = statisticsService.waitForChart(
                appId,
                LocalDate.parse(startDate),
                LocalDate.parse(endDate),
                maxWaitSeconds
        );
        return ApiResponse.success(result);
    }

    @GetMapping("/task/{taskId}")
    public ApiResponse<Map<String, Object>> getTaskStatus(@PathVariable String taskId) {
        Map<String, Object> status = statisticsService.getTaskStatus(taskId);
        return ApiResponse.success(status);
    }

    @PostMapping("/refresh/{appId}")
    public ApiResponse<Map<String, Object>> forceRefreshSummary(@PathVariable String appId) {
        Map<String, Object> result = statisticsService.forceRefreshSummary(appId);
        return ApiResponse.success("统计数据已刷新", result);
    }

    @GetMapping("/cache/{appId}")
    public ApiResponse<Map<String, Object>> getCacheInfo(@PathVariable String appId) {
        Map<String, Object> info = statisticsService.getCacheInfo(appId);
        return ApiResponse.success(info);
    }

    @PostMapping("/demo/{appId}")
    public ApiResponse<List<Statistics>> generateDemoData(
            @PathVariable String appId,
            @RequestParam(defaultValue = "30") int days) {
        List<Statistics> data = statisticsService.generateDemoData(appId, days);
        return ApiResponse.success("演示数据生成成功，统计缓存已更新", data);
    }
}
