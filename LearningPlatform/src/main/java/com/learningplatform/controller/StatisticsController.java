
package com.learningplatform.controller;

import com.learningplatform.dto.ApiResponse;
import com.learningplatform.entity.Statistics;
import com.learningplatform.service.AnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {

    @Autowired
    private AnalysisService analysisService;

    @GetMapping
    public ApiResponse<Statistics> getCurrentStatistics() {
        Statistics stats = analysisService.getCurrentStatistics();
        return ApiResponse.success(stats);
    }

    @PostMapping("/refresh")
    public ApiResponse<Statistics> refreshStatistics() {
        Statistics stats = analysisService.refreshStatistics();
        return ApiResponse.success(stats);
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> getOverview() {
        Statistics stats = analysisService.getCurrentStatistics();
        Map<String, Object> overview = new HashMap<>();
        overview.put("courseCount", stats.getCourseCount());
        overview.put("studentCount", stats.getStudentCount());
        overview.put("enrollmentCount", stats.getEnrollmentCount());
        overview.put("completionCount", stats.getCompletionCount());
        overview.put("certificateCount", stats.getCertificateCount());
        overview.put("reviewCount", stats.getReviewCount());
        overview.put("averageRating", stats.getAverageRating());
        overview.put("completionRate", analysisService.calculateCompletionRate());
        overview.put("certificateRate", analysisService.calculateCertificateRate());
        return ApiResponse.success(overview);
    }
}
