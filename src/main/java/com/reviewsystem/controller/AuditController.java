package com.reviewsystem.controller;

import com.reviewsystem.dto.ApiResponse;
import com.reviewsystem.model.AuditRecord;
import com.reviewsystem.model.Comment;
import com.reviewsystem.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private static final Logger logger = LoggerFactory.getLogger(AuditController.class);

    @Autowired
    private AuditService auditService;

    @PostMapping("/{commentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> auditComment(
            @PathVariable String commentId,
            @RequestParam(required = false) String auditor) {
        logger.info("执行自动审核: commentId={}", commentId);

        Map<String, Object> result = auditService.auditComment(commentId, auditor);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.ok(ApiResponse.error(400, (String) result.get("message")));
        }
    }

    @PostMapping("/manual/{commentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> manualAudit(
            @PathVariable String commentId,
            @RequestBody Map<String, String> request) {
        String decision = request.get("decision");
        String reason = request.get("reason");
        String auditor = request.get("auditor");

        if (decision == null) {
            return ResponseEntity.ok(ApiResponse.badRequest("缺少审核决策"));
        }

        logger.info("执行人工审核: commentId={}, decision={}", commentId, decision);

        Map<String, Object> result = auditService.manualAudit(
                commentId, auditor != null ? auditor : "admin", decision, reason);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.ok(ApiResponse.error(400, (String) result.get("message")));
        }
    }

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<Comment>>> getPendingComments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<Comment> comments = auditService.getPendingComments(page, size);
        return ResponseEntity.ok(ApiResponse.success(comments));
    }

    @GetMapping("/comment/{commentId}")
    public ResponseEntity<ApiResponse<List<AuditRecord>>> getAuditRecords(
            @PathVariable String commentId) {
        List<AuditRecord> records = auditService.getAuditRecords(commentId);
        return ResponseEntity.ok(ApiResponse.success(records));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getAuditStats() {
        Map<String, Long> stats = auditService.getAuditStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/pending/count")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPendingCount() {
        long count = auditService.countPendingComments();
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("pending_count", count);
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
