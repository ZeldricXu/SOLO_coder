package com.scheduler.notification.service;

import com.scheduler.notification.channel.NotificationChannel;
import com.scheduler.persistence.entity.Notification;
import com.scheduler.persistence.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final List<NotificationChannel> channels;
    private final NotificationMapper notificationMapper;

    public Mono<Notification> send(Notification notification) {
        notification.setNotificationId("notif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        notification.setStatus("PENDING");
        notification.setRetryCount(0);
        notificationMapper.insert(notification);
        return doSend(notification);
    }

    private Mono<Notification> doSend(Notification notification) {
        return channels.stream()
                .filter(channel -> channel.supports(notification.getChannel()))
                .findFirst()
                .map(channel -> channel.send(notification)
                        .flatMap(success -> {
                            if (success) {
                                notification.setStatus("DELIVERED");
                                notification.setDeliveredAt(Instant.now());
                            } else {
                                handleFailure(notification, "Channel send failed");
                            }
                            notificationMapper.updateById(notification);
                            return Mono.just(notification);
                        })
                        .onErrorResume(e -> {
                            handleFailure(notification, e.getMessage());
                            notificationMapper.updateById(notification);
                            return Mono.just(notification);
                        }))
                .orElseGet(() -> {
                    notification.setStatus("FAILED");
                    notification.setErrorMessage("No channel found for: " + notification.getChannel());
                    notificationMapper.updateById(notification);
                    return Mono.just(notification);
                });
    }

    private void handleFailure(Notification notification, String errorMessage) {
        notification.setRetryCount(notification.getRetryCount() + 1);
        notification.setErrorMessage(errorMessage);
        if (notification.getRetryCount() >= notification.getMaxRetries()) {
            notification.setStatus("FAILED");
            log.error("Notification {} failed after {} retries", notification.getNotificationId(), notification.getRetryCount());
        } else {
            notification.setStatus("PENDING");
            log.warn("Notification {} failed, will retry (attempt {}/{})",
                    notification.getNotificationId(), notification.getRetryCount(), notification.getMaxRetries());
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void retryPendingNotifications() {
        List<Notification> pending = notificationMapper.findPendingRetries();
        if (!pending.isEmpty()) {
            log.info("Retrying {} pending notifications", pending.size());
            Flux.fromIterable(pending)
                    .flatMap(this::doSend)
                    .subscribe();
        }
    }

    public Notification findById(String notificationId) {
        return notificationMapper.findByNotificationId(notificationId);
    }

    public List<Notification> findByTraceId(String traceId) {
        return notificationMapper.findByTraceId(traceId);
    }

    public List<String> getAvailableChannels() {
        return channels.stream().map(NotificationChannel::getChannelName).toList();
    }
}
