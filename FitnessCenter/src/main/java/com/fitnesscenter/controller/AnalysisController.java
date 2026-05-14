package com.fitnesscenter.controller;

import com.fitnesscenter.dto.ApiResponse;
import com.fitnesscenter.model.Statistic;
import com.fitnesscenter.service.AnalysisService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @GetMapping("/monthly/{month}")
    public ApiResponse<Statistic> getMonthlyStatistics(@PathVariable String month) {
        Statistic statistic = analysisService.getMonthlyStatistics(month);
        return ApiResponse.success(statistic);
    }

    @GetMapping("/current-month")
    public ApiResponse<Statistic> getCurrentMonthStatistics() {
        Statistic statistic = analysisService.getCurrentMonthStatistics();
        return ApiResponse.success(statistic);
    }

    @GetMapping("/member/{memberId}")
    public ApiResponse<Map<String, Object>> getMemberAnalysis(@PathVariable String memberId) {
        Map<String, Object> analysis = analysisService.getMemberAnalysis(memberId);
        return ApiResponse.success(analysis);
    }

    @GetMapping("/effect/{memberId}")
    public ApiResponse<Map<String, Object>> getEffectAnalysis(@PathVariable String memberId) {
        Map<String, Object> effect = analysisService.getEffectAnalysis(memberId);
        return ApiResponse.success(effect);
    }

    @GetMapping
    public ApiResponse<List<Statistic>> getAllStatistics() {
        List<Statistic> statistics = analysisService.getAllStatistics();
        return ApiResponse.success(statistics);
    }
}
