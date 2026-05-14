package com.houserental.controller;

import com.houserental.dto.ApiResponse;
import com.houserental.entity.Statistics;
import com.houserental.service.StatisticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {

    @Autowired
    private StatisticsService statisticsService;

    @GetMapping("/overview")
    public ApiResponse<Statistics> getComprehensiveStatistics() {
        Statistics stat = statisticsService.getComprehensiveStatistics();
        return ApiResponse.success(stat);
    }

    @GetMapping("/month/{month}")
    public ApiResponse<Statistics> getStatisticsByMonth(@PathVariable String month) {
        Statistics stat = statisticsService.getStatisticsByMonth(month);
        if (stat == null) {
            return ApiResponse.error(404, "该月份统计数据不存在");
        }
        return ApiResponse.success(stat);
    }

    @GetMapping("/range")
    public ApiResponse<List<Statistics>> getStatisticsByMonthRange(
            @RequestParam String startMonth,
            @RequestParam String endMonth) {
        List<Statistics> stats = statisticsService.getStatisticsByMonthRange(startMonth, endMonth);
        return ApiResponse.success(stats);
    }

    @GetMapping("/recent")
    public ApiResponse<List<Statistics>> getRecentStatistics(
            @RequestParam(defaultValue = "12") int limit) {
        List<Statistics> stats = statisticsService.getRecentStatistics(limit);
        return ApiResponse.success(stats);
    }

    @PostMapping("/refresh")
    public ApiResponse<Statistics> refreshCurrentStatistics() {
        Statistics stat = statisticsService.refreshCurrentStatistics();
        return ApiResponse.success(stat);
    }
}
