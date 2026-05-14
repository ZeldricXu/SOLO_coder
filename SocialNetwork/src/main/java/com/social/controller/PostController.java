package com.social.controller;

import com.social.dto.ApiResponse;
import com.social.entity.Interaction;
import com.social.entity.Post;
import com.social.entity.PostNotification;
import com.social.service.PostPushService;
import com.social.service.PostService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/posts")
public class PostController {

    @Autowired
    private PostService postService;

    @Autowired
    private PostPushService postPushService;

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createPost(@RequestBody Map<String, Object> request) {
        String userId = (String) request.get("user_id");
        String postContent = (String) request.get("post_content");
        String postType = (String) request.get("post_type");

        Post post = postService.createPost(userId, postContent, postType);
        
        Map<String, Object> data = new HashMap<>();
        data.put("post_id", post.getPostId());
        data.put("status", post.getPostStatus());

        return ApiResponse.success(data);
    }

    @GetMapping("/{postId}")
    public ApiResponse<Post> getPost(@PathVariable String postId) {
        Post post = postService.getPostById(postId);
        return ApiResponse.success(post);
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<Post>> getUserPosts(@PathVariable String userId) {
        List<Post> posts = postService.getUserPosts(userId);
        return ApiResponse.success(posts);
    }

    @GetMapping("/list")
    public ApiResponse<List<Post>> listAllPosts() {
        List<Post> posts = postService.getAllPublishedPosts();
        return ApiResponse.success(posts);
    }

    @PostMapping("/{postId}/like")
    public ApiResponse<Post> likePost(@PathVariable String postId, @RequestBody Map<String, String> request) {
        String userId = request.get("user_id");
        if (userId == null) {
            return ApiResponse.error(400, "用户ID不能为空");
        }
        Post post = postService.likePost(postId, userId);
        return ApiResponse.success(post);
    }

    @PostMapping("/{postId}/comment")
    public ApiResponse<Post> commentPost(@PathVariable String postId, @RequestBody Map<String, Object> request) {
        String userId = (String) request.get("user_id");
        String commentContent = (String) request.get("comment_content");

        if (userId == null) {
            return ApiResponse.error(400, "用户ID不能为空");
        }
        Post post = postService.commentPost(postId, userId, commentContent);
        return ApiResponse.success(post);
    }

    @GetMapping("/{postId}/interactions")
    public ApiResponse<List<Interaction>> getPostInteractions(@PathVariable String postId) {
        List<Interaction> interactions = postService.getPostInteractions(postId);
        return ApiResponse.success(interactions);
    }

    @GetMapping("/count")
    public ApiResponse<Map<String, Object>> getPostCount() {
        Map<String, Object> result = new HashMap<>();
        result.put("total_posts", postService.countTotalPosts());
        result.put("total_interactions", postService.countTotalInteractions());
        return ApiResponse.success(result);
    }

    @GetMapping("/notifications/{userId}")
    public ApiResponse<List<PostNotification>> getUserNotifications(@PathVariable String userId) {
        List<PostNotification> notifications = postPushService.getUserNotifications(userId);
        return ApiResponse.success(notifications);
    }

    @GetMapping("/notifications/{userId}/unread")
    public ApiResponse<List<PostNotification>> getUnreadNotifications(@PathVariable String userId) {
        List<PostNotification> notifications = postPushService.getUnreadNotifications(userId);
        return ApiResponse.success(notifications);
    }

    @PostMapping("/notifications/{notificationId}/read")
    public ApiResponse<PostNotification> markNotificationAsRead(
            @PathVariable String notificationId,
            @RequestBody Map<String, String> request) {
        String userId = request.get("user_id");
        if (userId == null) {
            return ApiResponse.error(400, "用户ID不能为空");
        }
        PostNotification notification = postPushService.markNotificationAsRead(notificationId, userId);
        return ApiResponse.success(notification);
    }

    @GetMapping("/notifications/{userId}/unread-count")
    public ApiResponse<Map<String, Object>> countUnreadNotifications(@PathVariable String userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("unread_count", postPushService.countUnreadNotifications(userId));
        return ApiResponse.success(result);
    }

    @GetMapping("/notifications/queued-count")
    public ApiResponse<Map<String, Object>> countQueuedNotifications() {
        Map<String, Object> result = new HashMap<>();
        result.put("queued_count", postPushService.countQueuedNotifications());
        return ApiResponse.success(result);
    }
}
