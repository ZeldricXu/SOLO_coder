package com.reviewsystem.controller;

import com.reviewsystem.dto.ApiResponse;
import com.reviewsystem.model.Comment;
import com.reviewsystem.service.RecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/recommend")
public class RecommendController {

    private static final Logger logger = LoggerFactory.getLogger(RecommendController.class);

    @Autowired
    private RecommendService recommendService;

    @GetMapping("/{contentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRecommendations(
            @PathVariable String contentId) {
        Map<String, Object> ranking = recommendService.getCommentRanking(contentId);
        return ResponseEntity.ok(ApiResponse.success(ranking));
    }

    @GetMapping("/hot/{contentId}")
    public ResponseEntity<ApiResponse<List<Comment>>> getHotComments(
            @PathVariable String contentId,
            @RequestParam(defaultValue = "10") int limit) {
        List<Comment> comments = recommendService.getHotComments(contentId, limit);
        return ResponseEntity.ok(ApiResponse.success(comments));
    }

    @GetMapping("/quality/{contentId}")
    public ResponseEntity<ApiResponse<List<Comment>>> getQualityComments(
            @PathVariable String contentId,
            @RequestParam(defaultValue = "10") int limit) {
        List<Comment> comments = recommendService.getQualityComments(contentId, limit);
        return ResponseEntity.ok(ApiResponse.success(comments));
    }

    @GetMapping("/positive/{contentId}")
    public ResponseEntity<ApiResponse<List<Comment>>> getPositiveComments(
            @PathVariable String contentId,
            @RequestParam(defaultValue = "10") int limit) {
        List<Comment> comments = recommendService.getPositiveComments(contentId, limit);
        return ResponseEntity.ok(ApiResponse.success(comments));
    }

    @GetMapping("/latest/{contentId}")
    public ResponseEntity<ApiResponse<List<Comment>>> getLatestComments(
            @PathVariable String contentId,
            @RequestParam(defaultValue = "10") int limit) {
        List<Comment> comments = recommendService.getLatestComments(contentId, limit);
        return ResponseEntity.ok(ApiResponse.success(comments));
    }

    @GetMapping("/comment/{commentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCommentRecommendation(
            @PathVariable String commentId) {
        Map<String, Object> info = recommendService.getCommentRecommendation(commentId);
        return ResponseEntity.ok(ApiResponse.success(info));
    }

    @PostMapping("/recalculate/{commentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> recalculateScore(
            @PathVariable String commentId) {
        recommendService.recalculateRecommendScore(commentId);
        Map<String, Object> info = recommendService.getCommentRecommendation(commentId);
        return ResponseEntity.ok(ApiResponse.success("重新计算完成", info));
    }

    @PostMapping("/update/{contentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateAllScores(
            @PathVariable String contentId) {
        recommendService.updateRecommendScores(contentId);
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("content_id", contentId);
        result.put("message", "所有推荐分数已更新");
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
