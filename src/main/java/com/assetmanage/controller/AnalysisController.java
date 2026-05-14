package com.assetmanage.controller;

import com.assetmanage.dto.ApiResponse;
import com.assetmanage.entity.AssetStatistic;
import com.assetmanage.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;

    @GetMapping("/today")
    public ApiResponse<AssetStatistic> getTodayStatistics() {
        AssetStatistic stat = analysisService.getTodayStatistics();
        return ApiResponse.success(stat);
    }

    @GetMapping("/range")
    public ApiResponse<List<AssetStatistic>> getStatisticsByRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        List<AssetStatistic> stats = analysisService.getStatisticsByDateRange(start, end);
        return ApiResponse.success(stats);
    }

    @GetMapping("/report")
    public ApiResponse<Map<String, Object>> generateReport() {
        Map<String, Object> report = analysisService.generateReport();
        return ApiResponse.success(report);
    }

    @GetMapping
    public ApiResponse<List<AssetStatistic>> getAllStatistics() {
        List<AssetStatistic> stats = analysisService.getAllStatistics();
        return ApiResponse.success(stats);
    }
}
