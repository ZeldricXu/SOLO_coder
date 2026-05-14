package com.cms.controller;

import com.cms.dto.ApiResponse;
import com.cms.dto.CommentCreateRequest;
import com.cms.entity.Comment;
import com.cms.service.CommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/v1/comments")
public class CommentController {

    @Autowired
    private CommentService commentService;

    @PostMapping
    public ApiResponse<Comment> createComment(@Valid @RequestBody CommentCreateRequest request) {
        Comment comment = commentService.createComment(request);
        return ApiResponse.success(comment);
    }

    @GetMapping("/{commentId}")
    public ApiResponse<Comment> getComment(@PathVariable String commentId) {
        Comment comment = commentService.getCommentById(commentId);
        return ApiResponse.success(comment);
    }

    @GetMapping("/content/{contentId}")
    public ApiResponse<List<Comment>> getCommentsByContent(@PathVariable String contentId) {
        List<Comment> comments = commentService.getCommentsByContentId(contentId);
        return ApiResponse.success(comments);
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<Comment>> getCommentsByUser(@PathVariable String userId) {
        List<Comment> comments = commentService.getCommentsByUserId(userId);
        return ApiResponse.success(comments);
    }

    @GetMapping("/{commentId}/replies")
    public ApiResponse<List<Comment>> getReplies(@PathVariable String commentId) {
        List<Comment> replies = commentService.getReplies(commentId);
        return ApiResponse.success(replies);
    }

    @PostMapping("/{commentId}/like")
    public ApiResponse<Comment> likeComment(@PathVariable String commentId) {
        Comment comment = commentService.likeComment(commentId);
        return ApiResponse.success(comment);
    }

    @PutMapping("/{commentId}/status")
    public ApiResponse<Comment> updateCommentStatus(@PathVariable String commentId, @RequestParam String status) {
        Comment comment = commentService.updateCommentStatus(commentId, status);
        return ApiResponse.success(comment);
    }

    @DeleteMapping("/{commentId}")
    public ApiResponse<Void> deleteComment(@PathVariable String commentId) {
        commentService.deleteComment(commentId);
        return ApiResponse.success(null);
    }

    @GetMapping("/content/{contentId}/count")
    public ApiResponse<Long> countComments(@PathVariable String contentId) {
        long count = commentService.countCommentsByContentId(contentId);
        return ApiResponse.success(count);
    }
}
