package com.logmanager.service.impl;

import com.logmanager.common.enums.NotificationPriority;
import com.logmanager.domain.event.DomainEvent;
import com.logmanager.domain.event.EventPublisher;
import com.logmanager.domain.model.Notification;
import com.logmanager.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final EventPublisher eventPublisher;

    private final Map<String, Notification> notificationStore = new ConcurrentHashMap<>();
    private final Map<String, Instant> suppressionStore = new ConcurrentHashMap<>();

    @Override
    public Mono<Notification> sendNotification(String title, String content, NotificationPriority priority, String recipient, String channel, Map<String, Object> payload) {
        Notification notification = buildNotification(title, content, priority, recipient, channel, payload);
        return processNotification(notification);
    }

    @Override
    public Mono<Notification> sendNotificationWithSuppression(String title, String content, NotificationPriority priority, String recipient, String channel, Map<String, Object> payload, String suppressionKey, Duration suppressionDuration) {
        return isSuppressed(suppressionKey)
                .flatMap(suppressed -> {
                    if (suppressed && priority.getLevel() < NotificationPriority.HIGH.getLevel()) {
                        log.info("Notification suppressed: {}", title);
                        Notification notification = buildNotification(title, content, priority, recipient, channel, payload);
                        notification.setStatus("suppressed");
                        notification.setSuppressionKey(suppressionKey);
                        notificationStore.put(notification.getNotificationId(), notification);
                        return Mono.just(notification);
                    }
                    Notification notification = buildNotification(title, content, priority, recipient, channel, payload);
                    notification.setSuppressionKey(suppressionKey);
                    return processNotification(notification)
                            .doOnSuccess(n -> suppress(suppressionKey, suppressionDuration).subscribe());
                });
    }

    @Override
    public Mono<Notification> getNotification(String id) {
        Notification notification = notificationStore.get(id);
        return notification != null ? Mono.just(notification) : Mono.empty();
    }

    @Override
    public Flux<Notification> getNotificationsByRecipient(String recipient) {
        return Flux.fromIterable(notificationStore.values())
                .filter(n -> recipient.equals(n.getRecipient()));
    }

    @Override
    public Flux<Notification> getNotificationsByPriority(NotificationPriority priority) {
        return Flux.fromIterable(notificationStore.values())
                .filter(n -> priority.equals(n.getPriority()));
    }

    @Override
    public Flux<Notification> getPendingNotifications() {
        return Flux.fromIterable(notificationStore.values())
                .filter(n -> "pending".equals(n.getStatus()));
    }

    @Override
    public Mono<Void> markAsSent(String id) {
        Notification notification = notificationStore.get(id);
        if (notification != null) {
            notification.setStatus("sent");
            notification.setSentAt(Instant.now());
            eventPublisher.publish(new DomainEvent("notification.sent", id, "notification"));
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> markAsFailed(String id, String error) {
        Notification notification = notificationStore.get(id);
        if (notification != null) {
            notification.setStatus("failed");
            notification.setErrorDetail(error);
            eventPublisher.publish(new DomainEvent("notification.failed", id, "notification"));
        }
        return Mono.empty();
    }

    @Override
    public Mono<Boolean> isSuppressed(String suppressionKey) {
        Instant suppressedUntil = suppressionStore.get(suppressionKey);
        if (suppressedUntil == null) {
            return Mono.just(false);
        }
        if (Instant.now().isAfter(suppressedUntil)) {
            suppressionStore.remove(suppressionKey);
            return Mono.just(false);
        }
        return Mono.just(true);
    }

    @Override
    public Mono<Void> suppress(String suppressionKey, Duration duration) {
        suppressionStore.put(suppressionKey, Instant.now().plus(duration));
        log.info("Suppression set for key: {}, duration: {}", suppressionKey, duration);
        return Mono.empty();
    }

    @Override
    public Mono<Void> clearSuppression(String suppressionKey) {
        suppressionStore.remove(suppressionKey);
        log.info("Suppression cleared for key: {}", suppressionKey);
        return Mono.empty();
    }

    private Notification buildNotification(String title, String content, NotificationPriority priority, String recipient, String channel, Map<String, Object> payload) {
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID().toString());
        notification.setNotificationId(UUID.randomUUID().toString());
        notification.setTitle(title);
        notification.setContent(content);
        notification.setPriority(priority);
        notification.setRecipient(recipient);
        notification.setChannel(channel);
        notification.setPayload(payload);
        notification.setStatus("pending");
        notification.setCreatedAt(Instant.now());
        notification.setUpdatedAt(Instant.now());
        return notification;
    }

    private Mono<Notification> processNotification(Notification notification) {
        notificationStore.put(notification.getNotificationId(), notification);
        log.info("Notification queued: {} to {} via {}", notification.getTitle(), notification.getRecipient(), notification.getChannel());
        eventPublisher.publish(new DomainEvent("notification.queued", notification.getNotificationId(), "notification"));
        return deliverNotification(notification)
                .thenReturn(notification);
    }

    private Mono<Void> deliverNotification(Notification notification) {
        return Mono.fromRunnable(() -> {
            try {
                log.info("Delivering notification [{}]: {}", notification.getPriority(), notification.getTitle());
                notification.setStatus("sent");
                notification.setSentAt(Instant.now());
                eventPublisher.publish(new DomainEvent("notification.sent", notification.getNotificationId(), "notification"));
            } catch (Exception e) {
                log.error("Failed to deliver notification: {}", notification.getNotificationId(), e);
                notification.setStatus("failed");
                notification.setErrorDetail(e.getMessage());
            }
        });
    }
}
