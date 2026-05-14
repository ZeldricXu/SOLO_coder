package com.cms.controller;

import com.cms.dto.ApiResponse;
import com.cms.dto.ContentCreateRequest;
import com.cms.entity.Content;
import com.cms.service.ContentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/contents")
public class ContentController {

    @Autowired
    private ContentService contentService;

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createContent(@Valid @RequestBody ContentCreateRequest request) {
        Content content = contentService.createContent(request);

        Map<String, Object> result = new HashMap<>();
        result.put("content_id", content.getContentId());
        result.put("status", content.getContentStatus());
        result.put("created_at", content.getCreatedAt());
        result.put("content_type", content.getContentType());

        return ApiResponse.success(result);
    }

    @PutMapping("/{contentId}")
    public ApiResponse<Content> updateContent(@PathVariable String contentId, @Valid @RequestBody ContentCreateRequest request) {
        Content content = contentService.updateContent(contentId, request);
        return ApiResponse.success(content);
    }

    @GetMapping("/{contentId}")
    public ApiResponse<Content> getContent(@PathVariable String contentId) {
        Content content = contentService.getContentById(contentId);
        return ApiResponse.success(content);
    }

    @GetMapping
    public ApiResponse<List<Content>> getAllContents() {
        List<Content> contents = contentService.getAllContents();
        return ApiResponse.success(contents);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<Content>> getContentsByStatus(@PathVariable String status) {
        List<Content> contents = contentService.getContentsByStatus(status);
        return ApiResponse.success(contents);
    }

    @GetMapping("/category/{category}")
    public ApiResponse<List<Content>> getContentsByCategory(@PathVariable String category) {
        List<Content> contents = contentService.getContentsByCategory(category);
        return ApiResponse.success(contents);
    }

    @GetMapping("/author/{author}")
    public ApiResponse<List<Content>> getContentsByAuthor(@PathVariable String author) {
        List<Content> contents = contentService.getContentsByAuthor(author);
        return ApiResponse.success(contents);
    }

    @DeleteMapping("/{contentId}")
    public ApiResponse<Void> deleteContent(@PathVariable String contentId) {
        contentService.deleteContent(contentId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{contentId}/submit-review")
    public ApiResponse<Content> submitForReview(@PathVariable String contentId) {
        Content content = contentService.submitForReview(contentId);
        return ApiResponse.success(content);
    }

    @PostMapping("/{contentId}/view")
    public ApiResponse<Map<String, Object>> recordView(@PathVariable String contentId) {
        contentService.recordViewAsync(contentId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("content_id", contentId);
        result.put("status", "queued");
        result.put("message", "阅读统计已加入队列，将异步处理");
        
        return ApiResponse.success(result);
    }

    @PostMapping("/{contentId}/like")
    public ApiResponse<Map<String, Object>> recordLike(@PathVariable String contentId) {
        contentService.recordLikeAsync(contentId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("content_id", contentId);
        result.put("status", "queued");
        result.put("message", "点赞统计已加入队列，将异步处理");
        
        return ApiResponse.success(result);
    }

    @PostMapping("/{contentId}/share")
    public ApiResponse<Map<String, Object>> recordShare(@PathVariable String contentId) {
        contentService.recordShareAsync(contentId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("content_id", contentId);
        result.put("status", "queued");
        result.put("message", "分享统计已加入队列，将异步处理");
        
        return ApiResponse.success(result);
    }

    @GetMapping("/statistics/queue-status")
    public ApiResponse<Map<String, Object>> getStatisticsQueueStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("pending_view_tasks", contentService.getPendingViewTasks());
        status.put("pending_like_tasks", contentService.getPendingLikeTasks());
        status.put("pending_share_tasks", contentService.getPendingShareTasks());
        status.put("total_pending_tasks", contentService.getPendingStatisticsTasks());
        
        return ApiResponse.success(status);
    }
}
