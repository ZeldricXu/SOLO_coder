package com.reviewsystem.controller;

import com.reviewsystem.dto.ApiResponse;
import com.reviewsystem.model.QualityEvaluation;
import com.reviewsystem.service.EvaluationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/evaluation")
public class EvaluationController {

    private static final Logger logger = LoggerFactory.getLogger(EvaluationController.class);

    @Autowired
    private EvaluationService evaluationService;

    @PostMapping("/{commentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> evaluateComment(
            @PathVariable String commentId) {
        logger.info("执行质量评估: commentId={}", commentId);

        Map<String, Object> result = evaluationService.evaluateComment(commentId);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.ok(ApiResponse.error(400, (String) result.get("message")));
        }
    }

    @GetMapping("/{commentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEvaluationResult(
            @PathVariable String commentId) {
        Map<String, Object> result = evaluationService.getEvaluationResult(commentId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/violations")
    public ResponseEntity<ApiResponse<List<QualityEvaluation>>> getViolations(
            @RequestParam(defaultValue = "100") int limit) {
        List<QualityEvaluation> violations = evaluationService.getViolationList(limit);
        return ResponseEntity.ok(ApiResponse.success(violations));
    }

    @GetMapping("/spam")
    public ResponseEntity<ApiResponse<List<QualityEvaluation>>> getSpam(
            @RequestParam(defaultValue = "100") int limit) {
        List<QualityEvaluation> spams = evaluationService.getSpamList(limit);
        return ResponseEntity.ok(ApiResponse.success(spams));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getViolationStats() {
        Map<String, Long> stats = evaluationService.getViolationStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @PostMapping("/handle/{commentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleViolation(
            @PathVariable String commentId,
            @RequestBody Map<String, String> request) {
        String handler = request.get("handler");
        String action = request.get("action");
        String note = request.get("note");

        if (action == null) {
            return ResponseEntity.ok(ApiResponse.badRequest("缺少处理操作"));
        }

        logger.info("处理违规: commentId={}, action={}", commentId, action);

        Map<String, Object> result = evaluationService.handleViolation(
                commentId, handler != null ? handler : "admin", action, note);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.ok(ApiResponse.error(400, (String) result.get("message")));
        }
    }

    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchEvaluate(
            @RequestBody Map<String, List<String>> request) {
        List<String> commentIds = request.get("comment_ids");
        if (commentIds == null || commentIds.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.badRequest("缺少评论ID列表"));
        }

        evaluationService.batchEvaluate(commentIds);

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("processed_count", commentIds.size());
        result.put("message", "批量评估完成");

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
