package com.reviewsystem.controller;

import com.reviewsystem.dto.ApiResponse;
import com.reviewsystem.dto.CommentStatsDTO;
import com.reviewsystem.model.CommentStat;
import com.reviewsystem.service.AnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisController.class);

    @Autowired
    private AnalysisService analysisService;

    @GetMapping("/stats/{contentId}")
    public ResponseEntity<ApiResponse<CommentStatsDTO>> getStats(
            @PathVariable String contentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        CommentStatsDTO stats = analysisService.getCommentStats(contentId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/sentiment/{commentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSentimentAnalysis(
            @PathVariable String commentId) {
        Map<String, Object> analysis = analysisService.getSentimentAnalysis(commentId);
        return ResponseEntity.ok(ApiResponse.success(analysis));
    }

    @GetMapping("/quality/{commentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getQualityAnalysis(
            @PathVariable String commentId) {
        Map<String, Object> analysis = analysisService.getQualityAnalysis(commentId);
        return ResponseEntity.ok(ApiResponse.success(analysis));
    }

    @GetMapping("/daily/{contentId}")
    public ResponseEntity<ApiResponse<List<CommentStat>>> getDailyStats(
            @PathVariable String contentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<CommentStat> stats = analysisService.getDailyStats(contentId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOverview() {
        Map<String, Object> stats = analysisService.getOverallStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/trend/{contentId}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTrend(
            @PathVariable String contentId,
            @RequestParam(defaultValue = "7") int days) {
        List<Map<String, Object>> trends = analysisService.getTrendAnalysis(contentId, days);
        return ResponseEntity.ok(ApiResponse.success(trends));
    }
}
