package com.logistics.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Service
public class MemoryNotificationQueueService {

    private final Queue<Object> notificationQueue = new ConcurrentLinkedQueue<>();
    private final Queue<Object> failedQueue = new ConcurrentLinkedQueue<>();

    public boolean enqueueNotification(Object task) {
        boolean result = notificationQueue.offer(task);
        log.debug("通知入队内存队列: 结果: {}", result);
        return result;
    }

    @SuppressWarnings("unchecked")
    public <T> T dequeueNotification(Class<T> clazz) {
        Object task = notificationQueue.poll();
        if (task == null) {
            return null;
        }
        if (clazz.isInstance(task)) {
            return (T) task;
        }
        log.warn("队列中的对象类型不匹配: 期望 {}, 实际 {}", clazz.getName(), task.getClass().getName());
        return null;
    }

    public <T> T dequeueNotificationNonBlocking(Class<T> clazz) {
        return dequeueNotification(clazz);
    }

    public boolean enqueueFailedNotification(Object task) {
        boolean result = failedQueue.offer(task);
        log.debug("失败通知入队内存队列: 结果: {}", result);
        return result;
    }

    @SuppressWarnings("unchecked")
    public <T> T dequeueFailedNotification(Class<T> clazz) {
        Object task = failedQueue.poll();
        if (task == null) {
            return null;
        }
        if (clazz.isInstance(task)) {
            return (T) task;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public <T> java.util.List<T> getAllFailedNotifications(Class<T> clazz) {
        java.util.List<T> result = new java.util.ArrayList<>();
        for (Object task : failedQueue) {
            if (clazz.isInstance(task)) {
                result.add((T) task);
            }
        }
        return result;
    }

    public void clearFailedQueue() {
        failedQueue.clear();
        log.info("清空内存失败通知队列");
    }

    public long getQueueSize() {
        return notificationQueue.size();
    }

    public long getFailedQueueSize() {
        return failedQueue.size();
    }
}
