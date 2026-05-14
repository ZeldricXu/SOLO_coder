package com.reviewsystem.controller;

import com.reviewsystem.dto.ApiResponse;
import com.reviewsystem.dto.CommentEditRequest;
import com.reviewsystem.dto.CommentPublishRequest;
import com.reviewsystem.dto.CommentStatsDTO;
import com.reviewsystem.model.Comment;
import com.reviewsystem.model.CommentHistory;
import com.reviewsystem.model.ReplyRecord;
import com.reviewsystem.model.ReportRecord;
import com.reviewsystem.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/comments")
public class CommentController {

    private static final Logger logger = LoggerFactory.getLogger(CommentController.class);

    @Autowired
    private CommentService commentService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private RecommendService recommendService;

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private ReplyService replyService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private SortService sortService;

    @Autowired
    private HistoryService historyService;

    @PostMapping("/publish")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publishComment(
            @Valid @RequestBody CommentPublishRequest request) {
        logger.info("接收评论发布请求: userId={}, contentId={}",
                request.getUserId(), request.getContentId());

        Map<String, Object> result = commentService.publishComment(request);

        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> data = new HashMap<>();
            data.put("comment_id", result.get("comment_id"));
            data.put("status", result.get("status"));
            data.put("audit_result", result.get("audit_result"));
            if (result.containsKey("audit_reason")) {
                data.put("audit_reason", result.get("audit_reason"));
            }
            return ResponseEntity.ok(ApiResponse.success(data));
        } else {
            return ResponseEntity.ok(ApiResponse.error(400, (String) result.get("message")));
        }
    }

    @PostMapping("/edit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> editComment(
            @Valid @RequestBody CommentEditRequest request) {
        logger.info("接收评论编辑请求: commentId={}", request.getCommentId());

        Map<String, Object> result = commentService.editComment(request);

        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> data = new HashMap<>();
            data.put("comment_id", result.get("comment_id"));
            data.put("status", result.get("status"));
            data.put("message", result.get("message"));
            return ResponseEntity.ok(ApiResponse.success(data));
        } else {
            return ResponseEntity.ok(ApiResponse.error(400, (String) result.get("message")));
        }
    }

    @GetMapping("/{commentId}")
    public ResponseEntity<ApiResponse<Comment>> getComment(@PathVariable String commentId) {
        Optional<Comment> comment = commentService.getComment(commentId);
        if (comment.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(comment.get()));
        } else {
            return ResponseEntity.ok(ApiResponse.notFound("评论不存在"));
        }
    }

    @GetMapping("/content/{contentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCommentsByContent(
            @PathVariable String contentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "newest") String sort) {

        List<Comment> comments = sortService.sortComments(contentId, sort, page, size);
        long total = commentService.countCommentsByContent(contentId);

        Map<String, Object> data = new HashMap<>();
        data.put("comments", comments);
        data.put("total", total);
        data.put("page", page);
        data.put("size", size);
        data.put("sort_type", sort);

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<ApiResponse<List<Comment>>> getCommentsByUser(
            @PathVariable String userId) {
        List<Comment> comments = commentService.getCommentsByUser(userId);
        return ResponseEntity.ok(ApiResponse.success(comments));
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteComment(
            @PathVariable String commentId,
            @RequestParam(required = false) String operator) {
        boolean result = commentService.deleteComment(commentId,
                operator != null ? operator : "system");

        if (result) {
            Map<String, Object> data = new HashMap<>();
            data.put("comment_id", commentId);
            data.put("status", "deleted");
            return ResponseEntity.ok(ApiResponse.success(data));
        } else {
            return ResponseEntity.ok(ApiResponse.notFound("评论不存在"));
        }
    }

    @PostMapping("/report")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reportComment(
            @RequestBody Map<String, String> request) {
        String commentId = request.get("comment_id");
        String reportType = request.get("report_type");
        String reportReason = request.get("report_reason");
        String reportUserId = request.get("report_user_id");

        if (commentId == null || reportType == null || reportReason == null) {
            return ResponseEntity.ok(ApiResponse.badRequest("缺少必要参数"));
        }

        logger.info("接收评论举报请求: commentId={}, type={}", commentId, reportType);

        Map<String, Object> params = new HashMap<>();
        params.put("commentId", commentId);
        params.put("reportType", reportType);
        params.put("reportReason", reportReason);
        params.put("reportUserId", reportUserId);

        com.reviewsystem.dto.ReportRequest req = new com.reviewsystem.dto.ReportRequest();
        req.setCommentId(commentId);
        req.setReportType(reportType);
        req.setReportReason(reportReason);
        req.setReportUserId(reportUserId);

        Map<String, Object> result = reportService.submitReport(req);

        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> data = new HashMap<>();
            data.put("report_id", result.get("report_id"));
            data.put("status", result.get("status"));
            return ResponseEntity.ok(ApiResponse.success(data));
        } else {
            return ResponseEntity.ok(ApiResponse.error(400, (String) result.get("message")));
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCommentStats(
            @RequestParam String contentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        CommentStatsDTO stats = analysisService.getCommentStats(contentId, startDate, endDate);

        Map<String, Object> data = new HashMap<>();
        data.put("total_comments", stats.getTotalComments());
        data.put("published_comments", stats.getPublishedComments());
        data.put("rejected_comments", stats.getRejectedComments());
        data.put("pending_comments", stats.getPendingComments());
        data.put("avg_quality", stats.getAvgQuality());
        data.put("avg_sentiment", stats.getAvgSentiment());
        data.put("positive_count", stats.getPositiveCount());
        data.put("negative_count", stats.getNegativeCount());
        data.put("report_count", stats.getReportCount());
        data.put("total_likes", stats.getTotalLikes());
        data.put("total_replies", stats.getTotalReplies());

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/sort-types")
    public ResponseEntity<ApiResponse<Map<String, String>>> getSortTypes() {
        Map<String, String> sortTypes = sortService.getSortTypes();
        return ResponseEntity.ok(ApiResponse.success(sortTypes));
    }

    @GetMapping("/recommend/{contentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRecommendations(
            @PathVariable String contentId,
            @RequestParam(defaultValue = "10") int limit) {
        Map<String, Object> ranking = recommendService.getCommentRanking(contentId);
        return ResponseEntity.ok(ApiResponse.success(ranking));
    }

    @GetMapping("/{commentId}/history")
    public ResponseEntity<ApiResponse<List<CommentHistory>>> getCommentHistory(
            @PathVariable String commentId) {
        List<CommentHistory> history = historyService.getCommentHistory(commentId);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/{commentId}/replies")
    public ResponseEntity<ApiResponse<List<ReplyRecord>>> getReplies(
            @PathVariable String commentId) {
        List<ReplyRecord> replies = replyService.getReplies(commentId);
        return ResponseEntity.ok(ApiResponse.success(replies));
    }

    @GetMapping("/{commentId}/analysis")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAnalysis(
            @PathVariable String commentId) {
        Map<String, Object> analysis = new HashMap<>();
        analysis.put("sentiment", analysisService.getSentimentAnalysis(commentId));
        analysis.put("quality", analysisService.getQualityAnalysis(commentId));
        analysis.put("recommendation", recommendService.getCommentRecommendation(commentId));
        return ResponseEntity.ok(ApiResponse.success(analysis));
    }
}
