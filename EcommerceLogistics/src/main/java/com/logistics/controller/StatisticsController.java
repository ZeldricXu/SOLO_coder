package com.logistics.controller;

import com.logistics.dto.ApiResponse;
import com.logistics.entity.Statistics;
import com.logistics.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    @GetMapping("/current")
    public ApiResponse<Statistics> getCurrentStatistics() {
        Statistics statistics = statisticsService.getCurrentStatistics();
        return ApiResponse.success(statistics);
    }

    @GetMapping("/list")
    public ApiResponse<List<Statistics>> getAllStatistics() {
        List<Statistics> statisticsList = statisticsService.getAllStatistics();
        return ApiResponse.success(statisticsList);
    }
}
