package com.parking.platform.notification.service;

import com.parking.platform.common.constant.Constants;
import com.parking.platform.notification.entity.Notification;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final Map<String, Notification> notificationStore = new ConcurrentHashMap<>();
    private final Map<String, NotificationChannel> channels = new ConcurrentHashMap<>();

    public NotificationService() {
        channels.put("email", new EmailChannel());
        channels.put("sms", new SmsChannel());
        channels.put("webhook", new WebhookChannel());
    }

    public Notification sendNotification(Notification notification) {
        if (notification.getChannel() == null) {
            notification.setChannel("email");
        }
        notification.setStatus("SENDING");
        notification.setSentAt(Instant.now());
        notificationStore.put(notification.getId(), notification);
        log.info("Notification created: {}", notification.getId());

        sendWithRetry(notification);
        return notification;
    }

    @Retry(name = "notification", fallbackMethod = "handleSendFailure")
    public void sendWithRetry(Notification notification) {
        NotificationChannel channel = channels.get(notification.getChannel());
        if (channel == null) {
            throw new RuntimeException("Unknown channel: " + notification.getChannel());
        }

        boolean success = channel.send(notification);
        notification.recordAttempt(success, success ? "sent" : "failed");

        if (success) {
            notification.setStatus("DELIVERED");
            notification.setDeliveredAt(Instant.now());
            log.info("Notification delivered: {}", notification.getId());
        } else {
            throw new RuntimeException("Failed to send notification");
        }
    }

    public void handleSendFailure(Notification notification, Exception ex) {
        notification.setRetryCount(notification.getRetryCount() + 1);
        if (notification.canRetry()) {
            notification.setStatus("RETRYING");
            log.warn("Notification failed, retrying: {}, attempt {}/{}",
                    notification.getId(), notification.getRetryCount(), notification.getMaxRetries());
        } else {
            notification.setStatus("FAILED");
            notification.setFailedAt(Instant.now());
            notification.setErrorMessage(ex.getMessage());
            log.error("Notification failed permanently: {}", notification.getId(), ex);
        }
    }

    public Notification getNotification(String id) {
        return notificationStore.get(id);
    }

    public List<Notification> listNotifications(String status, Integer page, Integer size) {
        List<Notification> notifications = new ArrayList<>(notificationStore.values());

        if (status != null) {
            notifications.removeIf(n -> !status.equals(n.getStatus()));
        }

        notifications.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        int pageNum = page != null ? page : 1;
        int sizeNum = size != null ? size : 20;
        int start = (pageNum - 1) * sizeNum;
        int end = Math.min(start + sizeNum, notifications.size());

        return start >= notifications.size() ? new ArrayList<>() : notifications.subList(start, end);
    }

    public Map<String, Long> getStatistics() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", (long) notificationStore.size());
        stats.put("pending", notificationStore.values().stream().filter(n -> "PENDING".equals(n.getStatus())).count());
        stats.put("sending", notificationStore.values().stream().filter(n -> "SENDING".equals(n.getStatus())).count());
        stats.put("delivered", notificationStore.values().stream().filter(n -> "DELIVERED".equals(n.getStatus())).count());
        stats.put("failed", notificationStore.values().stream().filter(n -> "FAILED".equals(n.getStatus())).count());
        return stats;
    }

    public interface NotificationChannel {
        String getName();
        boolean send(Notification notification);
    }

    public static class EmailChannel implements NotificationChannel {
        @Override public String getName() { return "email"; }
        @Override public boolean send(Notification notification) {
            log.info("Sending email to: {}", notification.getRecipient());
            return true;
        }
    }

    public static class SmsChannel implements NotificationChannel {
        @Override public String getName() { return "sms"; }
        @Override public boolean send(Notification notification) {
            log.info("Sending SMS to: {}", notification.getRecipient());
            return true;
        }
    }

    public static class WebhookChannel implements NotificationChannel {
        @Override public String getName() { return "webhook"; }
        @Override public boolean send(Notification notification) {
            log.info("Sending webhook to: {}", notification.getRecipient());
            return true;
        }
    }
}
