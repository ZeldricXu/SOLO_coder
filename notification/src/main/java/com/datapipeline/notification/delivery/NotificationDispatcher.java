package com.datapipeline.notification.delivery;

import com.datapipeline.notification.Notification;
import com.datapipeline.notification.queue.NotificationQueue;
import com.datapipeline.notification.suppression.SuppressionStrategy;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Slf4j
public class NotificationDispatcher {

    private final NotificationQueue queue;
    private final SuppressionStrategy suppressionStrategy;
    private final Map<Notification.Channel, NotificationSender> senders = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private volatile boolean running = false;

    public NotificationDispatcher(NotificationQueue queue, SuppressionStrategy suppressionStrategy) {
        this.queue = queue;
        this.suppressionStrategy = suppressionStrategy;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "notification-dispatcher");
            t.setDaemon(true);
            return t;
        });
    }

    public void registerSender(Notification.Channel channel, NotificationSender sender) {
        senders.put(channel, sender);
    }

    public void send(Notification notification) {
        if (suppressionStrategy.shouldSuppress(notification)) {
            notification.setStatus(Notification.Status.SUPPRESSED);
            log.info("Notification suppressed: id={}, type={}",
                    notification.getNotificationId(), notification.getType());
            return;
        }
        queue.offer(notification);
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        executor.submit(this::dispatchLoop);
        log.info("Notification dispatcher started");
    }

    public void stop() {
        running = false;
        executor.shutdown();
        log.info("Notification dispatcher stopped");
    }

    private void dispatchLoop() {
        while (running) {
            try {
                Notification notification = queue.poll(1000);
                if (notification == null) {
                    continue;
                }
                dispatch(notification);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Notification dispatch failed", e);
            }
        }
    }

    private void dispatch(Notification notification) {
        List<Notification.Channel> channels = notification.getChannels();
        if (channels == null || channels.isEmpty()) {
            log.warn("No channels specified for notification: id={}", notification.getNotificationId());
            return;
        }

        boolean anySuccess = false;
        for (Notification.Channel channel : channels) {
            NotificationSender sender = senders.get(channel);
            if (sender == null) {
                log.warn("No sender registered for channel: {}", channel);
                continue;
            }
            try {
                sender.send(notification);
                anySuccess = true;
                log.info("Notification sent via {}: id={}", channel, notification.getNotificationId());
            } catch (Exception e) {
                log.error("Failed to send notification via {}: id={}", channel, notification.getNotificationId(), e);
            }
        }

        if (anySuccess) {
            notification.setStatus(Notification.Status.SENT);
        } else {
            notification.setStatus(Notification.Status.FAILED);
        }
    }

    @FunctionalInterface
    public interface NotificationSender {
        void send(Notification notification) throws Exception;
    }

}
