package com.mobilestore.controller;

import com.mobilestore.common.ApiResponse;
import com.mobilestore.entity.Notification;
import com.mobilestore.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/notifications")
@CrossOrigin(origins = "*")
public class NotificationController {

    @Autowired
    private NotificationService notificationService;

    @GetMapping
    public ApiResponse<List<Notification>> getNotifications(
            @RequestParam String recipientId,
            @RequestParam(required = false) Boolean isRead) {
        List<Notification> notifications = notificationService.getNotifications(recipientId, isRead);
        return ApiResponse.success(notifications);
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> getUnreadCount(@RequestParam String recipientId) {
        long count = notificationService.getUnreadCount(recipientId);
        Map<String, Object> result = new HashMap<>();
        result.put("unread_count", count);
        return ApiResponse.success(result);
    }

    @PutMapping("/{notificationId}/read")
    public ApiResponse<Notification> markAsRead(@PathVariable String notificationId) {
        Notification notification = notificationService.markAsRead(notificationId);
        return ApiResponse.success("已标记为已读", notification);
    }

    @PutMapping("/read-all")
    public ApiResponse<Map<String, Object>> markAllAsRead(@RequestParam String recipientId) {
        int count = notificationService.markAllAsRead(recipientId);
        Map<String, Object> result = new HashMap<>();
        result.put("marked_count", count);
        return ApiResponse.success("已全部标记为已读", result);
    }
}
