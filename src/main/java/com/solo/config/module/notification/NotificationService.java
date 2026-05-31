package com.solo.config.module.notification;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.solo.config.common.IdGenerator;
import com.solo.config.entity.Notification;
import com.solo.config.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final List<NotificationChannel> channels;
    private final NotificationMapper notificationMapper;
    private final NotificationProperties properties;

    private final Map<String, WindowCounter> suppressionWindows = new ConcurrentHashMap<>();

    public Mono<Notification> sendNotification(String type, int priority, String title, String content, String recipient) {
        return Mono.fromCallable(() -> {
            String alertKey = type + ":" + recipient;
            if (shouldSuppress(alertKey)) {
                log.info("Notification suppressed, key: {}", alertKey);
                Notification notification = new Notification();
                notification.setNotificationId(IdGenerator.generateNotificationId());
                notification.setType(type);
                notification.setPriority(priority);
                notification.setTitle(title);
                notification.setContent(content);
                notification.setRecipient(recipient);
                notification.setStatus("suppressed");
                notification.setErrorMessage("Rate limited");
                notification.setCreatedAt(LocalDateTime.now());
                notificationMapper.insert(notification);
                return notification;
            }

            Notification notification = new Notification();
            notification.setNotificationId(IdGenerator.generateNotificationId());
            notification.setType(type);
            notification.setPriority(priority);
            notification.setTitle(title);
            notification.setContent(content);
            notification.setRecipient(recipient);
            notification.setStatus("pending");
            notificationMapper.insert(notification);

            doSendAsync(notification);

            return notification;
        });
    }

    private boolean shouldSuppress(String alertKey) {
        long windowSize = properties.getSuppression().getWindowSize();
        int maxAlerts = properties.getSuppression().getMaxAlertsPerWindow();

        WindowCounter counter = suppressionWindows.compute(alertKey, (k, v) -> {
            long now = System.currentTimeMillis();
            if (v == null || now - v.windowStart > windowSize) {
                return new WindowCounter(now, new AtomicInteger(1));
            }
            v.count.incrementAndGet();
            return v;
        });

        return counter.count.get() > maxAlerts;
    }

    @Async
    public void doSendAsync(Notification notification) {
        List<NotificationChannel> sortedChannels = channels.stream()
                .filter(NotificationChannel::isEnabled)
                .sorted(Comparator.comparingInt(NotificationChannel::getPriority))
                .toList();

        boolean sent = false;
        for (NotificationChannel channel : sortedChannels) {
            try {
                if (channel.send(notification.getRecipient(), notification.getTitle(), notification.getContent())) {
                    sent = true;
                    notification.setStatus("sent");
                    notification.setSentAt(LocalDateTime.now());
                    log.info("Notification sent via {}, id: {}", channel.getType(), notification.getNotificationId());
                    break;
                }
            } catch (Exception e) {
                log.error("Failed to send notification via {}", channel.getType(), e);
            }
        }

        if (!sent) {
            notification.setStatus("failed");
            notification.setErrorMessage("All channels failed");
        }

        notificationMapper.updateById(notification);
    }

    public Flux<Notification> listNotifications(String status) {
        return Flux.fromIterable(
                notificationMapper.selectList(
                        new QueryWrapper<Notification>()
                                .eq(status != null, "status", status)
                                .orderByDesc("created_at")
                                .last("LIMIT 100")
                )
        );
    }

    public Mono<Notification> getNotification(String notificationId) {
        return Mono.justOrEmpty(
                notificationMapper.selectOne(
                        new QueryWrapper<Notification>()
                                .eq("notification_id", notificationId)
                )
        );
    }

    private static class WindowCounter {
        final long windowStart;
        final AtomicInteger count;

        WindowCounter(long windowStart, AtomicInteger count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
