package com.crm.controller;

import com.crm.common.ApiResponse;
import com.crm.entity.Statistics;
import com.crm.service.AnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    @Autowired
    private AnalysisService analysisService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> getOverviewStatistics() {
        Map<String, Object> stats = analysisService.getOverviewStatistics();
        return ApiResponse.success(stats);
    }

    @GetMapping("/monthly/{month}")
    public ApiResponse<Statistics> getMonthlyStatistics(@PathVariable String month) {
        Statistics stats = analysisService.getMonthlyStatistics(month);
        return ApiResponse.success(stats);
    }
}
