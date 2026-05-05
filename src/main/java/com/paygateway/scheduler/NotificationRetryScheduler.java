package com.paygateway.scheduler;

import com.paygateway.service.AsyncNotificationService;
import com.paygateway.service.NotificationQueueItem;
import com.paygateway.service.RedisNotificationQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRetryScheduler {
    
    private final RedisNotificationQueueService redisNotificationQueueService;
    private final AsyncNotificationService asyncNotificationService;
    
    private static final int BATCH_SIZE = 50;
    
    @Scheduled(fixedDelay = 10000)
    public void processPendingRetries() {
        log.debug("开始执行Redis通知重试定时任务");
        
        try {
            List<NotificationQueueItem> pendingItems = redisNotificationQueueService.getReadyNotifications(BATCH_SIZE);
            
            if (pendingItems.isEmpty()) {
                log.debug("没有待处理的通知重试任务");
                return;
            }
            
            log.info("从Redis获取到 {} 个待处理通知重试任务", pendingItems.size());
            
            for (NotificationQueueItem item : pendingItems) {
                try {
                    asyncNotificationService.executeNotification(item);
                } catch (Exception e) {
                    log.error("执行通知重试任务失败：retryId={}", item.getRetryId(), e);
                    try {
                        redisNotificationQueueService.markForRetry(item, "执行异常：" + e.getMessage());
                    } catch (Exception ex) {
                        log.error("标记重试状态失败：retryId={}", item.getRetryId(), ex);
                    }
                }
            }
            
            log.info("Redis通知重试定时任务执行完成");
        } catch (Exception e) {
            log.error("Redis通知重试定时任务执行异常", e);
        }
    }
    
    @Scheduled(cron = "0 0 8 * * ?")
    public void reportFailedRetries() {
        log.info("开始执行失败通知报告任务");
        
        try {
            long pendingCount = redisNotificationQueueService.getPendingCount();
            long failedCount = redisNotificationQueueService.getFailedCount();
            
            log.info("通知队列统计：pendingCount={}, failedCount={}", pendingCount, failedCount);
            
            if (failedCount > 0) {
                List<NotificationQueueItem> failedItems = redisNotificationQueueService.getFailedNotifications();
                log.warn("发现 {} 个失败的通知重试任务，需要人工处理：", failedItems.size());
                for (NotificationQueueItem item : failedItems) {
                    log.warn("失败的通知重试：retryId={}, orderId={}, notifyUrl={}, retryCount={}, lastError={}", 
                            item.getRetryId(), item.getOrderId(), item.getNotifyUrl(), 
                            item.getRetryCount(), item.getLastErrorMsg());
                }
            }
            
        } catch (Exception e) {
            log.error("失败通知报告任务执行异常", e);
        }
    }
}
