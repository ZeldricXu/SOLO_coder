package com.contractmgmt.controller;

import com.contractmgmt.dto.ApiResponse;
import com.contractmgmt.entity.ContractStat;
import com.contractmgmt.service.StatisticsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {

    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> getStatistics() {
        Map<String, Object> stats = statisticsService.getStatistics();
        return ApiResponse.success(stats);
    }

    @GetMapping("/monthly/{month}")
    public ApiResponse<ContractStat> getMonthlyStat(@PathVariable String month) {
        ContractStat stat = statisticsService.getMonthlyStat(month);
        return ApiResponse.success(stat);
    }
}
