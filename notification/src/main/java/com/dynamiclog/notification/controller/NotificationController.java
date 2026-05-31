package com.dynamiclog.notification.controller;

import com.dynamiclog.common.dto.ApiResponse;
import com.dynamiclog.common.entity.Notification;
import com.dynamiclog.common.enums.NotificationStatus;
import com.dynamiclog.common.enums.NotificationType;
import com.dynamiclog.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    public Mono<ApiResponse<Notification>> sendNotification(
            @RequestParam NotificationType type,
            @RequestParam String recipient,
            @RequestParam(required = false) String subject,
            @RequestBody String content,
            @RequestParam(required = false) Map<String, Object> variables,
            @RequestParam(required = false) String traceId) {
        return notificationService.sendNotification(type, recipient, subject, content, variables, traceId)
                .map(ApiResponse::success);
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<Notification>> getNotification(@PathVariable String id) {
        return notificationService.getNotification(id)
                .map(ApiResponse::success);
    }

    @GetMapping("/{id}/status")
    public Mono<ApiResponse<NotificationStatus>> getNotificationStatus(@PathVariable String id) {
        return notificationService.getNotificationStatus(id)
                .map(ApiResponse::success);
    }

    @GetMapping("/trace/{traceId}")
    public Mono<ApiResponse<List<Notification>>> getNotificationsByTraceId(@PathVariable String traceId) {
        return notificationService.getNotificationsByTraceId(traceId)
                .collectList()
                .map(ApiResponse::success);
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getStats() {
        return notificationService.getStats()
                .map(ApiResponse::success);
    }
}
