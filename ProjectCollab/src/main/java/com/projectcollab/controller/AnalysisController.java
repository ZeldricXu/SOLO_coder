package com.projectcollab.controller;

import com.projectcollab.dto.ApiResponse;
import com.projectcollab.entity.ProjectStatistics;
import com.projectcollab.service.analysis.AnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    @Autowired
    private AnalysisService analysisService;

    @GetMapping("/statistics/monthly/{month}")
    public ApiResponse<ProjectStatistics> getMonthlyStatistics(@PathVariable String month) {
        ProjectStatistics stats = analysisService.getMonthlyStatistics(month);
        return ApiResponse.success(stats);
    }

    @GetMapping("/stats")
    public ApiResponse<ProjectStatistics> getCurrentStatistics() {
        ProjectStatistics stats = analysisService.getMonthlyStatistics(null);
        return ApiResponse.success(stats);
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> getOverview() {
        Map<String, Object> overview = new HashMap<>();
        overview.put("totalProjects", analysisService.getTotalProjects());
        overview.put("totalTasks", analysisService.getTotalTasks());
        overview.put("activeProjects", analysisService.getActiveProjectCount());
        overview.put("completedProjects", analysisService.getCompletedProjectCount());
        return ApiResponse.success(overview);
    }
}
