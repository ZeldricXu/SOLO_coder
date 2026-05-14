package com.medical.appointment.controller;

import com.medical.appointment.dto.ApiResponse;
import com.medical.appointment.entity.Statistics;
import com.medical.appointment.service.StatisticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {
    
    private final StatisticsService statisticsService;
    
    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }
    
    @GetMapping
    public ResponseEntity<ApiResponse<Statistics>> getCurrentMonthStatistics() {
        Statistics stats = statisticsService.getCurrentMonthStatistics();
        if (stats == null) {
            return ResponseEntity.ok(ApiResponse.success("暂无统计数据", null));
        }
        return ResponseEntity.ok(ApiResponse.success(stats));
    }
    
    @GetMapping("/month/{month}")
    public ResponseEntity<ApiResponse<Statistics>> getStatisticsByMonth(@PathVariable String month) {
        Statistics stats = statisticsService.getStatisticsByMonth(month);
        if (stats == null) {
            return ResponseEntity.ok(ApiResponse.success("该月暂无统计数据", null));
        }
        return ResponseEntity.ok(ApiResponse.success(stats));
    }
}
