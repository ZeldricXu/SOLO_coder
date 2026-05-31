package com.scheduler.api.controller;

import com.scheduler.common.model.ApiResponse;
import com.scheduler.notification.service.NotificationService;
import com.scheduler.persistence.entity.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    public Mono<ResponseEntity<ApiResponse<Notification>>> sendNotification(
            @RequestBody Notification notification) {
        return notificationService.send(notification)
                .map(n -> ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(n)));
    }

    @GetMapping("/{notificationId}")
    public Mono<ResponseEntity<ApiResponse<Notification>>> getNotification(
            @PathVariable String notificationId) {
        return Mono.fromCallable(() -> {
            Notification notification = notificationService.findById(notificationId);
            return ResponseEntity.ok(ApiResponse.success(notification));
        });
    }

    @GetMapping("/trace/{traceId}")
    public Mono<ResponseEntity<ApiResponse<List<Notification>>>> getNotificationsByTraceId(
            @PathVariable String traceId) {
        return Mono.fromCallable(() -> {
            List<Notification> notifications = notificationService.findByTraceId(traceId);
            return ResponseEntity.ok(ApiResponse.success(notifications));
        });
    }

    @GetMapping("/channels")
    public Mono<ResponseEntity<ApiResponse<List<String>>>> getAvailableChannels() {
        return Mono.fromCallable(() ->
                ResponseEntity.ok(ApiResponse.success(notificationService.getAvailableChannels()))
        );
    }
}
