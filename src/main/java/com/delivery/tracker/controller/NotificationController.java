package com.delivery.tracker.controller;

import com.delivery.tracker.common.Result;
import com.delivery.tracker.entity.Notification;
import com.delivery.tracker.notification.NotificationStrategy;
import com.delivery.tracker.service.NotificationService;
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
    public Mono<Result<Notification>> createNotification(@RequestBody Map<String, String> request) {
        String type = request.get("type");
        String recipient = request.get("recipient");
        String content = request.get("content");

        return notificationService.createNotification(type, recipient, content)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    @PostMapping("/{id}/send")
    public Mono<Result<Notification>> sendNotification(@PathVariable Long id) {
        return notificationService.getNotificationById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("通知不存在")))
                .flatMap(notificationService::sendNotification)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    @PostMapping("/{id}/send/strategy/{strategyType}")
    public Mono<Result<Notification>> sendNotificationWithStrategy(
            @PathVariable Long id,
            @PathVariable String strategyType) {
        return notificationService.getNotificationById(id)
                .switchIfEmpty(Mono.error(new RuntimeException("通知不存在")))
                .flatMap(n -> notificationService.sendNotificationWithStrategy(n, strategyType))
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    @PostMapping("/process")
    public Flux<Notification> processPendingNotifications() {
        return notificationService.processPendingNotifications();
    }

    @GetMapping("/{id}")
    public Mono<Result<Notification>> getNotification(@PathVariable Long id) {
        return notificationService.getNotificationStatus(id)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    @GetMapping("/status/{status}")
    public Flux<Notification> getNotificationsByStatus(@PathVariable String status) {
        return notificationService.getNotificationsByStatus(status);
    }

    @GetMapping("/strategies")
    public Mono<Result<List<String>>> getAvailableStrategies() {
        return notificationService.getAvailableStrategies()
                .map(Result::success);
    }

    @PostMapping("/strategies/register")
    public Mono<Result<Void>> registerStrategy(@RequestBody NotificationStrategy strategy) {
        return notificationService.registerStrategy(strategy)
                .then(Mono.just(Result.success()));
    }

    @DeleteMapping("/strategies/{type}")
    public Mono<Result<Void>> unregisterStrategy(@PathVariable String type) {
        return notificationService.unregisterStrategy(type)
                .then(Mono.just(Result.success()));
    }
}
