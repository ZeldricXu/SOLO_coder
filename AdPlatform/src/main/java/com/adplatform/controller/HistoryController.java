package com.adplatform.controller;

import com.adplatform.dto.ApiResponse;
import com.adplatform.entity.AdHistory;
import com.adplatform.service.HistoryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/ads/{adId}/history")
public class HistoryController {
    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping
    public ApiResponse<List<AdHistory>> getHistory(@PathVariable String adId) {
        List<AdHistory> histories = historyService.getHistoryByAdId(adId);
        return ApiResponse.success(histories);
    }

    @GetMapping("/type/{historyType}")
    public ApiResponse<List<AdHistory>> getHistoryByType(
            @PathVariable String adId,
            @PathVariable String historyType) {
        List<AdHistory> histories = historyService.getHistoryByAdIdAndType(adId, historyType);
        return ApiResponse.success(histories);
    }

    @GetMapping("/range")
    public ApiResponse<List<AdHistory>> getHistoryByTimeRange(
            @PathVariable String adId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        List<AdHistory> histories = historyService.getHistoryByAdIdAndTimeRange(adId, startTime, endTime);
        return ApiResponse.success(histories);
    }

    @GetMapping("/{historyId}")
    public ApiResponse<Optional<AdHistory>> getHistoryById(
            @PathVariable String adId,
            @PathVariable String historyId) {
        Optional<AdHistory> history = historyService.getHistoryById(historyId);
        return ApiResponse.success(history);
    }

    @GetMapping("/status")
    public ApiResponse<List<AdHistory>> getStatusChangeHistory(@PathVariable String adId) {
        List<AdHistory> histories = historyService.getStatusChangeHistory(adId);
        return ApiResponse.success(histories);
    }

    @GetMapping("/placement")
    public ApiResponse<List<AdHistory>> getPlacementHistory(@PathVariable String adId) {
        List<AdHistory> histories = historyService.getPlacementHistory(adId);
        return ApiResponse.success(histories);
    }

    @GetMapping("/budget")
    public ApiResponse<List<AdHistory>> getBudgetHistory(@PathVariable String adId) {
        List<AdHistory> histories = historyService.getBudgetHistory(adId);
        return ApiResponse.success(histories);
    }

    @GetMapping("/report")
    public ApiResponse<List<AdHistory>> getReportHistory(@PathVariable String adId) {
        List<AdHistory> histories = historyService.getReportHistory(adId);
        return ApiResponse.success(histories);
    }

    @GetMapping("/analysis")
    public ApiResponse<List<AdHistory>> getAnalysisHistory(@PathVariable String adId) {
        List<AdHistory> histories = historyService.getAnalysisHistory(adId);
        return ApiResponse.success(histories);
    }

    @GetMapping("/review")
    public ApiResponse<List<AdHistory>> getReviewHistory(@PathVariable String adId) {
        List<AdHistory> histories = historyService.getReviewHistory(adId);
        return ApiResponse.success(histories);
    }
}
