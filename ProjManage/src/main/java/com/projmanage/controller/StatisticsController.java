package com.projmanage.controller;

import com.projmanage.dto.ApiResponse;
import com.projmanage.model.Statistic;
import com.projmanage.service.StatisticsService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {

    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/project/{projectId}")
    public ApiResponse<List<Statistic>> getStatisticsByProject(@PathVariable String projectId) {
        return ApiResponse.success(statisticsService.getStatisticsByProject(projectId));
    }

    @GetMapping("/project/{projectId}/today")
    public ApiResponse<Statistic> getTodayStatistics(@PathVariable String projectId) {
        Optional<Statistic> statOpt = statisticsService.getTodayStatistics(projectId);
        if (statOpt.isPresent()) {
            return ApiResponse.success(statOpt.get());
        }
        return ApiResponse.error(404, "今日统计数据不存在");
    }

    @PostMapping("/project/{projectId}/refresh")
    public ApiResponse<Void> refreshStatistics(@PathVariable String projectId) {
        statisticsService.updateTaskStatistics(projectId);
        return ApiResponse.success(null);
    }
}
