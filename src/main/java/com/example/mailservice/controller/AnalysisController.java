package com.example.mailservice.controller;

import com.example.mailservice.dto.ApiResponse;
import com.example.mailservice.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Slf4j
@RestController
@RequestMapping("/api/v1/mail/statistics")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;

    @GetMapping("/today")
    public ApiResponse<AnalysisService.DailyReport> getTodayReport() {
        return ApiResponse.success(analysisService.getDailyReport(LocalDate.now()));
    }

    @GetMapping("/date/{date}")
    public ApiResponse<AnalysisService.DailyReport> getDailyReport(
            @PathVariable @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        return ApiResponse.success(analysisService.getDailyReport(date));
    }

    @GetMapping("/range")
    public ApiResponse<AnalysisService.RangeReport> getRangeReport(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate) {
        return ApiResponse.success(analysisService.getRangeReport(startDate, endDate));
    }
}
