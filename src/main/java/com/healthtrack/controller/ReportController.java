package com.healthtrack.controller;

import com.healthtrack.dto.ApiResponse;
import com.healthtrack.entity.HealthReport;
import com.healthtrack.service.ReportGenerationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    @Autowired
    private ReportGenerationService reportGenerationService;

    @PostMapping("/daily")
    public ResponseEntity<ApiResponse<HealthReport>> generateDailyReport(
            @RequestParam String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        try {
            HealthReport report = reportGenerationService.generateDailyReport(userId, date);
            return ResponseEntity.ok(ApiResponse.success(report));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "生成日报失败: " + e.getMessage()));
        }
    }

    @PostMapping("/monthly")
    public ResponseEntity<ApiResponse<HealthReport>> generateMonthlyReport(
            @RequestParam String userId,
            @RequestParam int year,
            @RequestParam int month) {
        try {
            HealthReport report = reportGenerationService.generateMonthlyReport(userId, year, month);
            return ResponseEntity.ok(ApiResponse.success(report));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "生成月报失败: " + e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<HealthReport>>> getUserReports(@RequestParam String userId) {
        try {
            List<HealthReport> reports = reportGenerationService.getUserReports(userId);
            return ResponseEntity.ok(ApiResponse.success(reports));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "查询报告失败: " + e.getMessage()));
        }
    }

    @GetMapping("/{reportId}")
    public ResponseEntity<ApiResponse<HealthReport>> getReportById(@PathVariable String reportId) {
        try {
            return reportGenerationService.getReportById(reportId)
                    .map(report -> ResponseEntity.ok(ApiResponse.success(report)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "查询报告失败: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{reportId}")
    public ResponseEntity<ApiResponse<Void>> deleteReport(@PathVariable String reportId) {
        try {
            reportGenerationService.deleteReport(reportId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "删除报告失败: " + e.getMessage()));
        }
    }
}
