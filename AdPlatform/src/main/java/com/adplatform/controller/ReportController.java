package com.adplatform.controller;

import com.adplatform.dto.ApiResponse;
import com.adplatform.entity.AdReport;
import com.adplatform.service.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/ads/{adId}/reports")
public class ReportController {
    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/daily")
    public ApiResponse<AdReport> generateDailyReport(
            @PathVariable String adId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportDate) {
        LocalDate date = reportDate != null ? reportDate : LocalDate.now();
        AdReport report = reportService.generateDailyReport(adId, date);
        return ApiResponse.success(report);
    }

    @PostMapping("/weekly")
    public ApiResponse<AdReport> generateWeeklyReport(
            @PathVariable String adId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        AdReport report = reportService.generateWeeklyReport(adId, end);
        return ApiResponse.success(report);
    }

    @PostMapping("/monthly")
    public ApiResponse<AdReport> generateMonthlyReport(
            @PathVariable String adId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        AdReport report = reportService.generateMonthlyReport(adId, end);
        return ApiResponse.success(report);
    }

    @PostMapping("/summary")
    public ApiResponse<Map<String, Object>> generateSummaryReport(
            @PathVariable String adId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Map<String, Object> summary = reportService.generateSummaryReport(adId, startDate, endDate);
        return ApiResponse.success(summary);
    }

    @GetMapping
    public ApiResponse<List<AdReport>> getReports(@PathVariable String adId) {
        List<AdReport> reports = reportService.getReportsByAdId(adId);
        return ApiResponse.success(reports);
    }

    @GetMapping("/type/{reportType}")
    public ApiResponse<List<AdReport>> getReportsByType(
            @PathVariable String adId,
            @PathVariable String reportType) {
        List<AdReport> reports = reportService.getReportsByAdIdAndType(adId, reportType);
        return ApiResponse.success(reports);
    }

    @GetMapping("/{reportId}")
    public ApiResponse<Optional<AdReport>> getReportById(
            @PathVariable String adId,
            @PathVariable String reportId) {
        Optional<AdReport> report = reportService.getReportById(reportId);
        return ApiResponse.success(report);
    }

    @GetMapping("/latest/{reportType}")
    public ApiResponse<Optional<AdReport>> getLatestReport(
            @PathVariable String adId,
            @PathVariable String reportType) {
        Optional<AdReport> report = reportService.getLatestReport(adId, reportType);
        return ApiResponse.success(report);
    }
}
