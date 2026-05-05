package com.paygateway.controller;

import com.paygateway.dto.ApiResponse;
import com.paygateway.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
public class AnalysisController {
    
    private final AnalysisService analysisService;
    
    @GetMapping("/daily")
    public ApiResponse<Map<String, Object>> getDailyStatistics(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        
        if (date == null) {
            date = LocalDate.now();
        }
        
        log.info("查询每日统计数据：date={}", date);
        
        Map<String, Object> result = analysisService.getDailyStatistics(date);
        
        return ApiResponse.success(result);
    }
    
    @GetMapping("/range")
    public ApiResponse<Map<String, Object>> getRangeStatistics(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate) {
        
        log.info("查询区间统计数据：startDate={}, endDate={}", startDate, endDate);
        
        Map<String, Object> result = analysisService.getRangeStatistics(startDate, endDate);
        
        return ApiResponse.success(result);
    }
}
