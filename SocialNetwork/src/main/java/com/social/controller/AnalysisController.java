package com.social.controller;

import com.social.dto.ApiResponse;
import com.social.entity.SocialStat;
import com.social.service.AnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    @Autowired
    private AnalysisService analysisService;

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getSocialStatistics() {
        Map<String, Object> stats = analysisService.getSocialStatistics();
        return ApiResponse.success(stats);
    }

    @GetMapping("/month/{month}")
    public ApiResponse<SocialStat> getMonthlyStats(@PathVariable String month) {
        SocialStat stat = analysisService.getMonthlyStats(month);
        return ApiResponse.success(stat);
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<Map<String, Object>> analyzeUserRelations(@PathVariable String userId) {
        Map<String, Object> relations = analysisService.analyzeUserRelations(userId);
        return ApiResponse.success(relations);
    }
}
