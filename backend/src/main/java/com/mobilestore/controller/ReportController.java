package com.mobilestore.controller;

import com.mobilestore.common.ApiResponse;
import com.mobilestore.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/v1/reports")
@CrossOrigin(origins = "*")
public class ReportController {

    @Autowired
    private ReportService reportService;

    @GetMapping("/daily")
    public ApiResponse<Map<String, Object>> getDailyReport(
            @RequestParam String appId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        Map<String, Object> report = reportService.generateDailyReport(appId, date);
        return ApiResponse.success(report);
    }

    @GetMapping("/weekly")
    public ApiResponse<Map<String, Object>> getWeeklyReport(
            @RequestParam String appId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (endDate == null) {
            endDate = LocalDate.now();
        }
        Map<String, Object> report = reportService.generateWeeklyReport(appId, endDate);
        return ApiResponse.success(report);
    }

    @GetMapping("/monthly")
    public ApiResponse<Map<String, Object>> getMonthlyReport(
            @RequestParam String appId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (endDate == null) {
            endDate = LocalDate.now();
        }
        Map<String, Object> report = reportService.generateMonthlyReport(appId, endDate);
        return ApiResponse.success(report);
    }

    @GetMapping("/custom")
    public ApiResponse<Map<String, Object>> getCustomReport(
            @RequestParam String appId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Map<String, Object> report = reportService.generateCustomReport(appId, startDate, endDate);
        return ApiResponse.success(report);
    }
}
