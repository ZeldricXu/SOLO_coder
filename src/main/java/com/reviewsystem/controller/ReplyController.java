package com.reviewsystem.controller;

import com.reviewsystem.dto.ApiResponse;
import com.reviewsystem.dto.ReplyRequest;
import com.reviewsystem.model.ReplyRecord;
import com.reviewsystem.service.ReplyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/replies")
public class ReplyController {

    private static final Logger logger = LoggerFactory.getLogger(ReplyController.class);

    @Autowired
    private ReplyService replyService;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> addReply(
            @Valid @RequestBody ReplyRequest request) {
        logger.info("添加回复: commentId={}, user={}",
                request.getCommentId(), request.getReplyUser());

        Map<String, Object> result = replyService.addReply(request);

        if (Boolean.TRUE.equals(result.get("success"))) {
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("reply_id", result.get("reply_id"));
            data.put("comment_id", result.get("comment_id"));
            data.put("reply_time", result.get("reply_time"));
            return ResponseEntity.ok(ApiResponse.success(data));
        } else {
            return ResponseEntity.ok(ApiResponse.error(400, (String) result.get("message")));
        }
    }

    @GetMapping("/{replyId}")
    public ResponseEntity<ApiResponse<ReplyRecord>> getReply(@PathVariable String replyId) {
        Optional<ReplyRecord> reply = replyService.getReply(replyId);
        if (reply.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(reply.get()));
        } else {
            return ResponseEntity.ok(ApiResponse.notFound("回复不存在"));
        }
    }

    @GetMapping("/comment/{commentId}")
    public ResponseEntity<ApiResponse<List<ReplyRecord>>> getRepliesByComment(
            @PathVariable String commentId) {
        List<ReplyRecord> replies = replyService.getReplies(commentId);
        return ResponseEntity.ok(ApiResponse.success(replies));
    }

    @GetMapping("/user/{replyUser}")
    public ResponseEntity<ApiResponse<List<ReplyRecord>>> getRepliesByUser(
            @PathVariable String replyUser) {
        List<ReplyRecord> replies = replyService.getRepliesByUser(replyUser);
        return ResponseEntity.ok(ApiResponse.success(replies));
    }

    @DeleteMapping("/{replyId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteReply(
            @PathVariable String replyId,
            @RequestParam(required = false) String operator) {
        Map<String, Object> result = replyService.deleteReply(replyId,
                operator != null ? operator : "system");

        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.ok(ApiResponse.error(400, (String) result.get("message")));
        }
    }

    @PostMapping("/{replyId}/like")
    public ResponseEntity<ApiResponse<Map<String, Object>>> likeReply(
            @PathVariable String replyId) {
        Map<String, Object> result = replyService.likeReply(replyId);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.ok(ApiResponse.error(400, (String) result.get("message")));
        }
    }

    @PutMapping("/{replyId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> editReply(
            @PathVariable String replyId,
            @RequestBody Map<String, String> request) {
        String newContent = request.get("content");
        String operator = request.get("operator");

        if (newContent == null || newContent.trim().isEmpty()) {
            return ResponseEntity.ok(ApiResponse.badRequest("回复内容不能为空"));
        }

        Map<String, Object> result = replyService.editReply(replyId, newContent,
                operator != null ? operator : "user");

        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.ok(ApiResponse.error(400, (String) result.get("message")));
        }
    }

    @GetMapping("/count/{commentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReplyCount(
            @PathVariable String commentId) {
        long count = replyService.countRepliesByComment(commentId);
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("comment_id", commentId);
        data.put("reply_count", count);
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
