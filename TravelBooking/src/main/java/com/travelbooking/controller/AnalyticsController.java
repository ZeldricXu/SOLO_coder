package com.travelbooking.controller;

import com.travelbooking.dto.ApiResponse;
import com.travelbooking.model.TravelStat;
import com.travelbooking.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping
    public ApiResponse<List<TravelStat>> getAllStats() {
        return ApiResponse.success(analyticsService.getAllStats());
    }

    @GetMapping("/current")
    public ApiResponse<TravelStat> getCurrentMonthStats() {
        Optional<TravelStat> stat = analyticsService.getCurrentMonthStats();
        return stat.map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "暂无统计数据"));
    }

    @GetMapping("/month/{month}")
    public ApiResponse<TravelStat> getStatsByMonth(@PathVariable String month) {
        Optional<TravelStat> stat = analyticsService.getStatByMonth(month);
        return stat.map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "该月份暂无统计数据"));
    }
}
