package com.finance.controller;

import com.finance.dto.ApiResponse;
import com.finance.entity.FinanceStat;
import com.finance.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;

    @GetMapping("/overview/{accountId}")
    public ApiResponse<Map<String, Object>> getFinancialOverview(@PathVariable String accountId) {
        Map<String, Object> overview = analysisService.getFinancialOverview(accountId);
        return ApiResponse.success(overview);
    }

    @GetMapping("/trend/{accountId}")
    public ApiResponse<Map<String, Object>> getTrendAnalysis(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "6") int months) {
        Map<String, Object> trend = analysisService.getTrendAnalysis(accountId, months);
        return ApiResponse.success(trend);
    }

    @GetMapping("/stats/{accountId}")
    public ApiResponse<List<FinanceStat>> getStatsByAccount(@PathVariable String accountId) {
        List<FinanceStat> stats = analysisService.getStatsByAccount(accountId);
        return ApiResponse.success(stats);
    }
}
