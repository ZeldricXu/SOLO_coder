package com.datapipeline.notification.queue;

import com.datapipeline.notification.Notification;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;

@Slf4j
public class NotificationQueue {

    private final PriorityBlockingQueue<QueuedNotification> queue;
    private final int maxCapacity;

    public NotificationQueue(int maxCapacity) {
        this.maxCapacity = maxCapacity;
        this.queue = new PriorityBlockingQueue<>(maxCapacity,
                Comparator.comparing((QueuedNotification n) -> n.notification().getPriority()).reversed()
                        .thenComparing(n -> n.createdAt()));
    }

    public boolean offer(Notification notification) {
        if (queue.size() >= maxCapacity) {
            log.warn("Notification queue is full, dropping notification: id={}", notification.getNotificationId());
            return false;
        }
        boolean added = queue.offer(new QueuedNotification(notification, System.currentTimeMillis()));
        if (added) {
            log.debug("Notification queued: id={}, priority={}",
                    notification.getNotificationId(), notification.getPriority());
        }
        return added;
    }

    public Notification poll(long timeoutMs) throws InterruptedException {
        QueuedNotification queued = queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        return queued != null ? queued.notification() : null;
    }

    public Notification take() throws InterruptedException {
        return queue.take().notification();
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public void clear() {
        queue.clear();
    }

    public record QueuedNotification(Notification notification, long createdAt) {}

}
