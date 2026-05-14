package com.logistics.controller;

import com.logistics.dto.ApiResponse;
import com.logistics.entity.Notification;
import com.logistics.service.StatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final StatusService statusService;

    @GetMapping("/logistics/{logisticsId}")
    public ApiResponse<List<Notification>> getNotificationsByLogisticsId(@PathVariable String logisticsId) {
        List<Notification> notifications = statusService.getNotificationsByLogisticsId(logisticsId);
        return ApiResponse.success(notifications);
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<Notification>> getNotificationsByUserId(@PathVariable String userId) {
        List<Notification> notifications = statusService.getNotificationsByUserId(userId);
        return ApiResponse.success(notifications);
    }

    @GetMapping("/user/{userId}/unread")
    public ApiResponse<List<Notification>> getUnreadNotificationsByUserId(@PathVariable String userId) {
        List<Notification> notifications = statusService.getUnreadNotificationsByUserId(userId);
        return ApiResponse.success(notifications);
    }

    @PostMapping("/{notifyId}/read")
    public ApiResponse<Void> markAsRead(@PathVariable String notifyId) {
        statusService.markAsRead(notifyId);
        return ApiResponse.success(null);
    }
}
