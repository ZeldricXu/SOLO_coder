package com.survey.controller;

import com.survey.dto.ApiResponse;
import com.survey.entity.AnalysisReport;
import com.survey.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;

    @PostMapping("/report")
    public ApiResponse<AnalysisReport> generateReport(@RequestParam String surveyId) {
        AnalysisReport report = analysisService.generateReport(surveyId);
        return ApiResponse.success("报告生成成功", report);
    }

    @GetMapping("/report/{reportId}")
    public ApiResponse<AnalysisReport> getReport(@PathVariable String reportId) {
        AnalysisReport report = analysisService.getReport(reportId);
        return ApiResponse.success(report);
    }

    @GetMapping("/report/{reportId}/content")
    public ApiResponse<Map<String, Object>> getReportContent(@PathVariable String reportId) {
        Map<String, Object> content = analysisService.getReportContent(reportId);
        return ApiResponse.success(content);
    }

    @GetMapping("/survey/{surveyId}/reports")
    public ApiResponse<List<AnalysisReport>> getReportsBySurvey(@PathVariable String surveyId) {
        List<AnalysisReport> reports = analysisService.getReportsBySurvey(surveyId);
        return ApiResponse.success(reports);
    }
}
