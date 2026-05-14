package com.logistics.service;

import com.logistics.entity.Notification;
import com.logistics.repository.NotificationRepository;
import com.logistics.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncNotificationService {

    private final NotificationRepository notificationRepository;
    private final UserOnlineService userOnlineService;

    private static final int MAX_RETRY_COUNT = 3;
    private static final long RETRY_DELAY_MS = 1000;
    private static final long FAILED_NOTIFICATION_CHECK_INTERVAL_MS = 5000;
    private static final int MAX_WORKERS = 5;

    private final Queue<NotificationTask> notificationQueue = new ConcurrentLinkedQueue<>();
    private final List<NotificationTask> failedTasks = new java.util.ArrayList<>();
    private final AtomicInteger activeWorkers = new AtomicInteger(0);

    @Async
    public void sendNotificationAsync(String logisticsId, String notifyType, String notifyStatus, String userId) {
        NotificationTask task = new NotificationTask();
        task.setLogisticsId(logisticsId);
        task.setNotifyType(notifyType);
        task.setNotifyStatus(notifyStatus);
        task.setUserId(userId);
        task.setCreatedAt(LocalDateTime.now());
        task.setRetryCount(0);

        notificationQueue.offer(task);
        log.info("通知已入队 - logisticsId: {}, type: {}, status: {}", logisticsId, notifyType, notifyStatus);
    }

    @Async
    public void sendNotificationAsync(String logisticsId, String notifyType, String notifyStatus) {
        sendNotificationAsync(logisticsId, notifyType, notifyStatus, null);
    }

    public Notification sendNotificationSync(String logisticsId, String notifyType, String notifyStatus, String userId) {
        Notification notification = new Notification();
        notification.setNotifyId(IdGenerator.generateNotifyId());
        notification.setLogisticsId(logisticsId);
        notification.setNotifyType(notifyType);
        notification.setNotifyStatus(notifyStatus);
        notification.setNotifyTime(LocalDateTime.now());
        notification.setUserId(userId);
        notification.setIsRead(false);

        return notificationRepository.save(notification);
    }

    @Async
    public void processNotificationQueue() {
        if (activeWorkers.incrementAndGet() > MAX_WORKERS) {
            activeWorkers.decrementAndGet();
            return;
        }

        try {
            while (!notificationQueue.isEmpty()) {
                NotificationTask task = notificationQueue.poll();
                if (task != null) {
                    processTask(task);
                }
            }
        } finally {
            activeWorkers.decrementAndGet();
        }
    }

    private void processTask(NotificationTask task) {
        try {
            boolean success = deliverNotification(task);
            if (!success) {
                handleDeliveryFailure(task);
            }
        } catch (Exception e) {
            log.error("处理通知任务异常 - logisticsId: {}", task.getLogisticsId(), e);
            handleDeliveryFailure(task);
        }
    }

    private boolean deliverNotification(NotificationTask task) {
        String userId = task.getUserId();
        boolean isOnline = userId != null && userOnlineService.isUserOnline(userId);

        if (isOnline) {
            return deliverOnlinePush(task);
        } else {
            return deliverOfflineStorage(task);
        }
    }

    private boolean deliverOnlinePush(NotificationTask task) {
        log.info("在线推送通知 - logisticsId: {}, userId: {}, status: {}", 
                task.getLogisticsId(), task.getUserId(), task.getNotifyStatus());
        
        try {
            sendNotificationSync(task.getLogisticsId(), task.getNotifyType(), 
                    task.getNotifyStatus(), task.getUserId());
            log.info("在线推送成功 - logisticsId: {}", task.getLogisticsId());
            return true;
        } catch (Exception e) {
            log.warn("在线推送失败，尝试离线存储 - logisticsId: {}", task.getLogisticsId(), e);
            return deliverOfflineStorage(task);
        }
    }

    private boolean deliverOfflineStorage(NotificationTask task) {
        log.info("离线存储通知 - logisticsId: {}, userId: {}, status: {}", 
                task.getLogisticsId(), task.getUserId(), task.getNotifyStatus());
        
        try {
            sendNotificationSync(task.getLogisticsId(), task.getNotifyType(), 
                    task.getNotifyStatus(), task.getUserId());
            log.info("离线存储成功 - logisticsId: {}", task.getLogisticsId());
            return true;
        } catch (Exception e) {
            log.error("离线存储失败 - logisticsId: {}", task.getLogisticsId(), e);
            return false;
        }
    }

    private void handleDeliveryFailure(NotificationTask task) {
        int retryCount = task.getRetryCount() + 1;
        task.setRetryCount(retryCount);

        if (retryCount < MAX_RETRY_COUNT) {
            log.warn("通知发送失败，准备重试 ({}/{}) - logisticsId: {}", 
                    retryCount, MAX_RETRY_COUNT, task.getLogisticsId());
            
            try {
                Thread.sleep(RETRY_DELAY_MS * retryCount);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            notificationQueue.offer(task);
        } else {
            log.error("通知发送失败，已达最大重试次数 - logisticsId: {}", task.getLogisticsId());
            synchronized (failedTasks) {
                failedTasks.add(task);
            }
        }
    }

    @Scheduled(fixedRate = FAILED_NOTIFICATION_CHECK_INTERVAL_MS)
    public void retryFailedTasks() {
        List<NotificationTask> toRetry;
        synchronized (failedTasks) {
            if (failedTasks.isEmpty()) {
                return;
            }
            toRetry = new java.util.ArrayList<>(failedTasks);
            failedTasks.clear();
        }

        for (NotificationTask task : toRetry) {
            task.setRetryCount(0);
            notificationQueue.offer(task);
            log.info("重试失败通知任务 - logisticsId: {}", task.getLogisticsId());
        }
    }

    @Scheduled(fixedRate = 1000)
    public void scheduledProcessQueue() {
        if (!notificationQueue.isEmpty()) {
            processNotificationQueue();
        }
    }

    public int getQueueSize() {
        return notificationQueue.size();
    }

    public int getFailedTaskCount() {
        synchronized (failedTasks) {
            return failedTasks.size();
        }
    }

    public int getActiveWorkerCount() {
        return activeWorkers.get();
    }

    public static class NotificationTask {
        private String logisticsId;
        private String notifyType;
        private String notifyStatus;
        private String userId;
        private LocalDateTime createdAt;
        private int retryCount;

        public String getLogisticsId() {
            return logisticsId;
        }

        public void setLogisticsId(String logisticsId) {
            this.logisticsId = logisticsId;
        }

        public String getNotifyType() {
            return notifyType;
        }

        public void setNotifyType(String notifyType) {
            this.notifyType = notifyType;
        }

        public String getNotifyStatus() {
            return notifyStatus;
        }

        public void setNotifyStatus(String notifyStatus) {
            this.notifyStatus = notifyStatus;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public void setRetryCount(int retryCount) {
            this.retryCount = retryCount;
        }
    }
}
