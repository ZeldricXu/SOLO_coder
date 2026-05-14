package com.restaurant.mgmt.controller;

import com.restaurant.mgmt.dto.ApiResponse;
import com.restaurant.mgmt.model.SalesStat;
import com.restaurant.mgmt.service.AnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    @Autowired
    private AnalysisService analysisService;

    @GetMapping("/today")
    public ApiResponse<SalesStat> getTodayStats() {
        SalesStat stat = analysisService.getTodayStats();
        return ApiResponse.success(stat);
    }

    @GetMapping("/date/{date}")
    public ApiResponse<SalesStat> getStatsByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        SalesStat stat = analysisService.getStatsByDate(date);
        return ApiResponse.success(stat);
    }

    @GetMapping("/range")
    public ApiResponse<List<SalesStat>> getStatsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<SalesStat> stats = analysisService.getStatsByDateRange(startDate, endDate);
        return ApiResponse.success(stats);
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> getSummaryStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Map<String, Object> summary = analysisService.getSummaryStats(startDate, endDate);
        return ApiResponse.success(summary);
    }

    @GetMapping("/top-dishes")
    public ApiResponse<List<Map<String, Object>>> getTopDishes(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        List<Map<String, Object>> topDishes = analysisService.getTopDishes(startDate, endDate, limit);
        return ApiResponse.success(topDishes);
    }

    @GetMapping("/daily-trend")
    public ApiResponse<Map<String, Object>> getDailyTrend(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Map<String, Object> trend = analysisService.getDailyTrend(startDate, endDate);
        return ApiResponse.success(trend);
    }
}
