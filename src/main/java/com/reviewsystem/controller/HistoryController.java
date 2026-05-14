package com.reviewsystem.controller;

import com.reviewsystem.dto.ApiResponse;
import com.reviewsystem.model.CommentHistory;
import com.reviewsystem.service.HistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    private static final Logger logger = LoggerFactory.getLogger(HistoryController.class);

    @Autowired
    private HistoryService historyService;

    @GetMapping("/comment/{commentId}")
    public ResponseEntity<ApiResponse<List<CommentHistory>>> getCommentHistory(
            @PathVariable String commentId) {
        List<CommentHistory> history = historyService.getCommentHistory(commentId);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/comment/{commentId}/range")
    public ResponseEntity<ApiResponse<List<CommentHistory>>> getHistoryByTimeRange(
            @PathVariable String commentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        List<CommentHistory> history = historyService.getCommentHistoryWithTimeRange(
                commentId, startTime, endTime);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/operator/{operator}")
    public ResponseEntity<ApiResponse<List<CommentHistory>>> getHistoryByOperator(
            @PathVariable String operator) {
        List<CommentHistory> history = historyService.getHistoryByOperator(operator);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/action/{actionType}")
    public ResponseEntity<ApiResponse<List<CommentHistory>>> getHistoryByAction(
            @PathVariable String actionType) {
        List<CommentHistory> history = historyService.getHistoryByActionType(actionType);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/content/{contentId}")
    public ResponseEntity<ApiResponse<List<CommentHistory>>> getHistoryByContent(
            @PathVariable String contentId) {
        List<CommentHistory> history = historyService.getHistoryByContentId(contentId);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/range")
    public ResponseEntity<ApiResponse<List<CommentHistory>>> getHistoryByTime(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        List<CommentHistory> history = historyService.getHistoryByTimeRange(startTime, endTime);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/recent")
    public ResponseEntity<ApiResponse<List<CommentHistory>>> getRecentHistory(
            @RequestParam(defaultValue = "50") int limit) {
        List<CommentHistory> history = historyService.getRecentHistory(limit);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getHistoryStats() {
        Map<String, Long> stats = historyService.getHistoryStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/comment/{commentId}/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getHistorySummary(
            @PathVariable String commentId) {
        Map<String, Object> summary = historyService.getCommentHistorySummary(commentId);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @GetMapping("/comment/{commentId}/trail")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAuditTrail(
            @PathVariable String commentId) {
        List<Map<String, Object>> trail = historyService.getAuditTrail(commentId);
        return ResponseEntity.ok(ApiResponse.success(trail));
    }
}
