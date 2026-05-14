package com.survey.controller;

import com.survey.dto.ApiResponse;
import com.survey.dto.StatQueryResponse;
import com.survey.service.AsyncStatisticsService;
import com.survey.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;
    private final AsyncStatisticsService asyncStatisticsService;

    @GetMapping("/query")
    public ApiResponse<StatQueryResponse> getStatistics(@RequestParam String surveyId) {
        StatQueryResponse response = statisticsService.getStatistics(surveyId);
        return ApiResponse.success(response);
    }

    @PostMapping("/refresh")
    public ApiResponse<StatQueryResponse> refreshStatistics(@RequestParam String surveyId) {
        statisticsService.updateStatistics(surveyId);
        StatQueryResponse response = statisticsService.getStatistics(surveyId);
        return ApiResponse.success("统计数据已刷新", response);
    }

    @PostMapping("/refresh/async")
    public ApiResponse<Map<String, Object>> refreshStatisticsAsync(@RequestParam String surveyId) {
        asyncStatisticsService.triggerStatUpdate(surveyId);
        Map<String, Object> result = new HashMap<>();
        result.put("surveyId", surveyId);
        result.put("message", "异步统计任务已提交");
        result.put("processing", true);
        return ApiResponse.success("异步统计已触发", result);
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatisticsStatus(@RequestParam String surveyId) {
        Map<String, Object> result = new HashMap<>();
        result.put("surveyId", surveyId);
        result.put("processing", asyncStatisticsService.isStatisticsProcessing(surveyId));
        result.put("pendingCount", asyncStatisticsService.getPendingStatisticsCount());
        return ApiResponse.success(result);
    }
}
