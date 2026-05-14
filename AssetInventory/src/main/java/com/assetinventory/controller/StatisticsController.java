package com.assetinventory.controller;

import com.assetinventory.dto.ApiResponse;
import com.assetinventory.entity.InventoryStatistics;
import com.assetinventory.service.StatisticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {

    private final StatisticsService statisticsService;

    @Autowired
    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/current")
    public ResponseEntity<ApiResponse<InventoryStatistics>> getCurrentMonthStatistics() {
        InventoryStatistics stats = statisticsService.getCurrentMonthStatistics();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/month/{month}")
    public ResponseEntity<ApiResponse<InventoryStatistics>> getStatisticsByMonth(@PathVariable String month) {
        InventoryStatistics stats = statisticsService.getStatisticsByMonth(month);
        if (stats == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "该月份统计数据不存在"));
        }
        return ResponseEntity.ok(ApiResponse.success(stats));
    }
}
