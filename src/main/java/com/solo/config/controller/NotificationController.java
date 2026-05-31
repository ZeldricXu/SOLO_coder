package com.solo.config.controller;

import com.solo.config.common.Result;
import com.solo.config.entity.Notification;
import com.solo.config.module.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    public Mono<Result<Notification>> sendNotification(@RequestBody Map<String, Object> request) {
        String type = (String) request.getOrDefault("type", "email");
        int priority = (Integer) request.getOrDefault("priority", 3);
        String title = (String) request.get("title");
        String content = (String) request.get("content");
        String recipient = (String) request.get("recipient");

        return notificationService.sendNotification(type, priority, title, content, recipient)
                .map(Result::success);
    }

    @GetMapping
    public Flux<Notification> listNotifications(@RequestParam(required = false) String status) {
        return notificationService.listNotifications(status);
    }

    @GetMapping("/{notificationId}")
    public Mono<Result<Notification>> getNotification(@PathVariable String notificationId) {
        return notificationService.getNotification(notificationId)
                .map(Result::success)
                .defaultIfEmpty(Result.error(404, "通知不存在"));
    }
}
