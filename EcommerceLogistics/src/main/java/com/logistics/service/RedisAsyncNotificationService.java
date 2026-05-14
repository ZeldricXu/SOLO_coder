package com.logistics.service;

import com.logistics.config.NotificationConfig;
import com.logistics.constant.LogisticsConstants;
import com.logistics.entity.Notification;
import com.logistics.repository.NotificationRepository;
import com.logistics.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.redis.host")
public class RedisAsyncNotificationService {

    private final NotificationRepository notificationRepository;
    private final UserOnlineService userOnlineService;
    private final RedisNotificationQueueService redisQueueService;
    private final NotificationConfig notificationConfig;

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

        boolean enqueued = redisQueueService.enqueueNotification(task);
        if (enqueued) {
            log.info("通知已入队Redis - logisticsId: {}, type: {}, status: {}", logisticsId, notifyType, notifyStatus);
        } else {
            log.error("通知入队Redis失败 - logisticsId: {}", logisticsId);
        }
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
        if (activeWorkers.incrementAndGet() > notificationConfig.getMaxWorkers()) {
            activeWorkers.decrementAndGet();
            return;
        }

        try {
            NotificationTask task;
            int processedCount = 0;
            while (processedCount < 100 && (task = redisQueueService.dequeueNotificationNonBlocking(NotificationTask.class)) != null) {
                processTask(task);
                processedCount++;
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

        if (retryCount < notificationConfig.getMaxRetryCount()) {
            log.warn("通知发送失败，准备重试 ({}/{}) - logisticsId: {}", 
                    retryCount, notificationConfig.getMaxRetryCount(), task.getLogisticsId());
            
            try {
                Thread.sleep(notificationConfig.getRetryDelayMs() * retryCount);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            redisQueueService.enqueueNotification(task);
        } else {
            log.error("通知发送失败，已达最大重试次数，存入失败队列 - logisticsId: {}", task.getLogisticsId());
            redisQueueService.enqueueFailedNotification(task);
        }
    }

    @Scheduled(fixedRateString = "${logistics.notification.failed-check-interval-ms:5000}")
    public void retryFailedTasks() {
        List<NotificationTask> toRetry = redisQueueService.getAllFailedNotifications(NotificationTask.class);
        if (toRetry.isEmpty()) {
            return;
        }

        redisQueueService.clearFailedQueue();

        for (NotificationTask task : toRetry) {
            task.setRetryCount(0);
            redisQueueService.enqueueNotification(task);
            log.info("重试失败通知任务 - logisticsId: {}", task.getLogisticsId());
        }
    }

    @Scheduled(fixedRate = 1000)
    public void scheduledProcessQueue() {
        if (getQueueSize() > 0) {
            processNotificationQueue();
        }
    }

    public long getQueueSize() {
        return redisQueueService.getQueueSize();
    }

    public long getFailedTaskCount() {
        return redisQueueService.getFailedQueueSize();
    }

    public int getActiveWorkerCount() {
        return activeWorkers.get();
    }

    public boolean isRedisAvailable() {
        return redisQueueService.isRedisAvailable();
    }

    public static class NotificationTask {
        private String logisticsId;
        private String notifyType;
        private String notifyStatus;
        private String userId;
        private LocalDateTime createdAt;
        private int retryCount;

        public NotificationTask() {
        }

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
