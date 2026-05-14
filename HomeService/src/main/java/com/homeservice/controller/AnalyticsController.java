package com.homeservice.controller;

import com.homeservice.dto.ApiResponse;
import com.homeservice.dto.StatisticsResponse;
import com.homeservice.entity.ServiceStat;
import com.homeservice.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    @Autowired
    private AnalyticsService analyticsService;

    @GetMapping("/overview")
    public ApiResponse<StatisticsResponse> getOverview() {
        StatisticsResponse stats = analyticsService.getOverallStatistics();
        return ApiResponse.success(stats);
    }

    @GetMapping("/monthly/{month}")
    public ApiResponse<ServiceStat> getMonthlyStat(@PathVariable String month) {
        ServiceStat stat = analyticsService.getMonthlyStat(month);
        return ApiResponse.success(stat);
    }
}
