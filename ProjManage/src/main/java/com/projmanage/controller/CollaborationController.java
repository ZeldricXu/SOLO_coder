package com.projmanage.controller;

import com.projmanage.dto.ApiResponse;
import com.projmanage.model.Discussion;
import com.projmanage.model.Notification;
import com.projmanage.service.CollaborationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/collaboration")
public class CollaborationController {

    private final CollaborationService collaborationService;

    public CollaborationController(CollaborationService collaborationService) {
        this.collaborationService = collaborationService;
    }

    @PostMapping("/comments")
    public ApiResponse<Discussion> addComment(@RequestParam String projectId,
                                               @RequestParam(required = false) String taskId,
                                               @RequestParam String author,
                                               @RequestParam String content) {
        Discussion discussion = collaborationService.addComment(projectId, taskId, author, content);
        return ApiResponse.success(discussion);
    }

    @GetMapping("/discussions/project/{projectId}")
    public ApiResponse<List<Discussion>> getDiscussionsByProject(@PathVariable String projectId) {
        return ApiResponse.success(collaborationService.getDiscussionsByProject(projectId));
    }

    @GetMapping("/discussions/task/{taskId}")
    public ApiResponse<List<Discussion>> getDiscussionsByTask(@PathVariable String taskId) {
        return ApiResponse.success(collaborationService.getDiscussionsByTask(taskId));
    }

    @GetMapping("/notifications/{recipientId}")
    public ApiResponse<List<Notification>> getNotifications(@PathVariable String recipientId) {
        return ApiResponse.success(collaborationService.getNotificationsByRecipient(recipientId));
    }

    @GetMapping("/notifications/{recipientId}/unread")
    public ApiResponse<List<Notification>> getUnreadNotifications(@PathVariable String recipientId) {
        return ApiResponse.success(collaborationService.getUnreadNotifications(recipientId));
    }

    @PutMapping("/notifications/{notificationId}/read")
    public ApiResponse<Void> markAsRead(@PathVariable String notificationId) {
        collaborationService.markNotificationAsRead(notificationId);
        return ApiResponse.success(null);
    }

    @PutMapping("/notifications/{recipientId}/read-all")
    public ApiResponse<Void> markAllAsRead(@PathVariable String recipientId) {
        collaborationService.markAllNotificationsAsRead(recipientId);
        return ApiResponse.success(null);
    }
}
