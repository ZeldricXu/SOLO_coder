package com.recruitment.controller;

import com.recruitment.analysis.AnalysisService;
import com.recruitment.common.dto.ApiResponse;
import com.recruitment.model.Statistics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
public class AnalysisController {
    private final AnalysisService analysisService;

    @GetMapping("/current")
    public ResponseEntity<ApiResponse<Statistics>> getCurrentMonthStatistics() {
        Statistics statistics = analysisService.getCurrentMonthStatistics();
        return ResponseEntity.ok(ApiResponse.success(statistics));
    }

    @GetMapping("/month/{month}")
    public ResponseEntity<ApiResponse<Statistics>> getStatisticsByMonth(@PathVariable String month) {
        Statistics statistics = analysisService.getStatisticsByMonth(month);
        return ResponseEntity.ok(ApiResponse.success(statistics));
    }

    @PostMapping("/increment/position")
    public ResponseEntity<ApiResponse<Void>> incrementPositionCount() {
        analysisService.incrementPositionCount();
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/increment/resume")
    public ResponseEntity<ApiResponse<Void>> incrementResumeCount() {
        analysisService.incrementResumeCount();
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/increment/screened")
    public ResponseEntity<ApiResponse<Void>> incrementScreenedCount() {
        analysisService.incrementScreenedCount();
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/increment/interview")
    public ResponseEntity<ApiResponse<Void>> incrementInterviewCount() {
        analysisService.incrementInterviewCount();
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/increment/hire")
    public ResponseEntity<ApiResponse<Void>> incrementHireCount() {
        analysisService.incrementHireCount();
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/increment/reject")
    public ResponseEntity<ApiResponse<Void>> incrementRejectCount() {
        analysisService.incrementRejectCount();
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
