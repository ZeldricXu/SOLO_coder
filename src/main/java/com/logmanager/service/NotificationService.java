package com.logmanager.service;

import com.logmanager.common.enums.NotificationPriority;
import com.logmanager.domain.model.Notification;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Map;

public interface NotificationService {
    Mono<Notification> sendNotification(String title, String content, NotificationPriority priority, String recipient, String channel, Map<String, Object> payload);
    Mono<Notification> sendNotificationWithSuppression(String title, String content, NotificationPriority priority, String recipient, String channel, Map<String, Object> payload, String suppressionKey, Duration suppressionDuration);
    Mono<Notification> getNotification(String id);
    Flux<Notification> getNotificationsByRecipient(String recipient);
    Flux<Notification> getNotificationsByPriority(NotificationPriority priority);
    Flux<Notification> getPendingNotifications();
    Mono<Void> markAsSent(String id);
    Mono<Void> markAsFailed(String id, String error);
    Mono<Boolean> isSuppressed(String suppressionKey);
    Mono<Void> suppress(String suppressionKey, Duration duration);
    Mono<Void> clearSuppression(String suppressionKey);
}
