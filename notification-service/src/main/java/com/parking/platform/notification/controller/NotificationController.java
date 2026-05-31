package com.parking.platform.notification.controller;

import com.parking.platform.common.dto.ApiResponse;
import com.parking.platform.notification.entity.Notification;
import com.parking.platform.notification.service.NotificationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping
    public ApiResponse<Notification> send(@RequestBody Notification notification) {
        Notification result = notificationService.sendNotification(notification);
        return ApiResponse.created(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<Notification> get(@PathVariable String id) {
        Notification notification = notificationService.getNotification(id);
        return notification != null ? ApiResponse.success(notification) : ApiResponse.notFound("Notification not found");
    }

    @GetMapping
    public ApiResponse<List<Notification>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return ApiResponse.success(notificationService.listNotifications(status, page, size));
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> getStats() {
        return ApiResponse.success(notificationService.getStatistics());
    }
}
