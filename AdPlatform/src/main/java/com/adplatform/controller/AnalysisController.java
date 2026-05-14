package com.adplatform.controller;

import com.adplatform.dto.ApiResponse;
import com.adplatform.service.AnalysisService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ads/{adId}/analysis")
public class AnalysisController {
    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> analyzePerformance(
            @PathVariable String adId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        LocalDate start = startDate != null ? startDate : end.minusDays(7);
        Map<String, Object> analysis = analysisService.analyzeAdPerformance(adId, start, end);
        return ApiResponse.success(analysis);
    }

    @GetMapping("/click-quality")
    public ApiResponse<Map<String, Object>> analyzeClickQuality(
            @RequestParam Long exposureCount,
            @RequestParam Long clickCount) {
        Map<String, Object> result = analysisService.analyzeClickQuality(exposureCount, clickCount);
        return ApiResponse.success(result);
    }

    @GetMapping("/conversion-quality")
    public ApiResponse<Map<String, Object>> analyzeConversionQuality(
            @RequestParam Long clickCount,
            @RequestParam Long conversionCount) {
        Map<String, Object> result = analysisService.analyzeConversionQuality(clickCount, conversionCount);
        return ApiResponse.success(result);
    }
}
