package com.cms.controller;

import com.cms.dto.ApiResponse;
import com.cms.entity.ContentStatistics;
import com.cms.entity.MonthlyStatistics;
import com.cms.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    @Autowired
    private AnalyticsService analyticsService;

    @GetMapping("/overall")
    public ApiResponse<Map<String, Object>> getOverallStatistics() {
        Map<String, Object> stats = analyticsService.getOverallStatistics();
        return ApiResponse.success(stats);
    }

    @GetMapping("/content/{contentId}")
    public ApiResponse<Map<String, Object>> getContentAnalytics(@PathVariable String contentId) {
        Map<String, Object> analytics = analyticsService.getContentAnalytics(contentId);
        if (analytics == null) {
            return ApiResponse.error(404, "内容不存在");
        }
        return ApiResponse.success(analytics);
    }

    @GetMapping("/content/{contentId}/statistics")
    public ApiResponse<ContentStatistics> getContentStatistics(@PathVariable String contentId) {
        ContentStatistics statistics = analyticsService.getContentStatistics(contentId);
        return ApiResponse.success(statistics);
    }

    @GetMapping("/monthly/{month}")
    public ApiResponse<MonthlyStatistics> getMonthlyStatistics(@PathVariable String month) {
        MonthlyStatistics statistics = analyticsService.getMonthlyStatistics(month);
        return ApiResponse.success(statistics);
    }

    @PostMapping("/monthly/update")
    public ApiResponse<MonthlyStatistics> updateMonthlyStatistics() {
        MonthlyStatistics statistics = analyticsService.updateMonthlyStatistics();
        return ApiResponse.success(statistics);
    }
}
