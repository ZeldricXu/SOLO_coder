package com.logmanager.api.controller;

import com.logmanager.api.dto.NotificationDTO;
import com.logmanager.api.vo.ApiResponse;
import com.logmanager.common.enums.NotificationPriority;
import com.logmanager.domain.model.Notification;
import com.logmanager.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    public Mono<ApiResponse<Notification>> sendNotification(@Valid @RequestBody NotificationDTO dto) {
        NotificationPriority priority = NotificationPriority.valueOf(dto.getPriority().toUpperCase());

        if (dto.getSuppressionKey() != null && dto.getSuppressionDuration() != null) {
            return notificationService.sendNotificationWithSuppression(
                    dto.getTitle(),
                    dto.getContent(),
                    priority,
                    dto.getRecipient(),
                    dto.getChannel(),
                    dto.getPayload(),
                    dto.getSuppressionKey(),
                    dto.getSuppressionDuration()
            ).map(ApiResponse::created);
        }

        return notificationService.sendNotification(
                dto.getTitle(),
                dto.getContent(),
                priority,
                dto.getRecipient(),
                dto.getChannel(),
                dto.getPayload()
        ).map(ApiResponse::created);
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<Notification>> getNotification(@PathVariable String id) {
        return notificationService.getNotification(id)
                .map(ApiResponse::success)
                .defaultIfEmpty(ApiResponse.error(404, "Notification not found"));
    }

    @GetMapping("/recipient/{recipient}")
    public Mono<ApiResponse<Flux<Notification>>> getNotificationsByRecipient(@PathVariable String recipient) {
        return Mono.just(ApiResponse.success(notificationService.getNotificationsByRecipient(recipient)));
    }

    @GetMapping("/priority/{priority}")
    public Mono<ApiResponse<Flux<Notification>>> getNotificationsByPriority(@PathVariable String priority) {
        NotificationPriority p = NotificationPriority.valueOf(priority.toUpperCase());
        return Mono.just(ApiResponse.success(notificationService.getNotificationsByPriority(p)));
    }

    @GetMapping("/pending")
    public Mono<ApiResponse<Flux<Notification>>> getPendingNotifications() {
        return Mono.just(ApiResponse.success(notificationService.getPendingNotifications()));
    }

    @PostMapping("/{id}/sent")
    public Mono<ApiResponse<Void>> markAsSent(@PathVariable String id) {
        return notificationService.markAsSent(id)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @PostMapping("/{id}/failed")
    public Mono<ApiResponse<Void>> markAsFailed(@PathVariable String id, @RequestBody String error) {
        return notificationService.markAsFailed(id, error)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/suppress/{key}")
    public Mono<ApiResponse<Boolean>> isSuppressed(@PathVariable String key) {
        return notificationService.isSuppressed(key)
                .map(ApiResponse::success);
    }

    @PostMapping("/suppress/{key}")
    public Mono<ApiResponse<Void>> suppress(@PathVariable String key, @RequestParam long durationSeconds) {
        return notificationService.suppress(key, java.time.Duration.ofSeconds(durationSeconds))
                .then(Mono.just(ApiResponse.success(null)));
    }

    @DeleteMapping("/suppress/{key}")
    public Mono<ApiResponse<Void>> clearSuppression(@PathVariable String key) {
        return notificationService.clearSuppression(key)
                .then(Mono.just(ApiResponse.success(null)));
    }
}
