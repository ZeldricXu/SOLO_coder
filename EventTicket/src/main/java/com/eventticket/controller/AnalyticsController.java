package com.eventticket.controller;

import com.eventticket.dto.ApiResponse;
import com.eventticket.dto.StatisticsResponse;
import com.eventticket.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    @Autowired
    private AnalyticsService analyticsService;

    @GetMapping("/monthly")
    public ApiResponse<StatisticsResponse> getMonthlyStatistics() {
        StatisticsResponse response = analyticsService.getMonthlyStatistics();
        return ApiResponse.success(response);
    }

    @GetMapping("/month/{month}")
    public ApiResponse<StatisticsResponse> getStatisticsByMonth(@PathVariable String month) {
        StatisticsResponse response = analyticsService.getStatisticsByMonth(month);
        return ApiResponse.success(response);
    }

    @GetMapping("/event/{eventId}")
    public ApiResponse<StatisticsResponse> getEventStatistics(@PathVariable String eventId) {
        StatisticsResponse response = analyticsService.getEventStatistics(eventId);
        return ApiResponse.success(response);
    }

    @PostMapping("/update")
    public ApiResponse<StatisticsResponse> updateStatistics() {
        analyticsService.updateMonthlyStatistics();
        StatisticsResponse response = analyticsService.getMonthlyStatistics();
        return ApiResponse.success(response);
    }
}
