package com.paygateway.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisNotificationQueueService {
    
    private final RedisTemplate<String, Object> redisTemplate;
    
    private static final String PENDING_QUEUE_KEY = "notification:pending";
    private static final String FAILED_SET_KEY = "notification:failed";
    private static final String PROCESSING_SET_KEY = "notification:processing";
    private static final String NOTIFICATION_PREFIX = "notification:data:";
    
    private static final int MAX_RETRY_COUNT = 5;
    private static final int[] RETRY_INTERVALS = {1, 5, 10, 30, 60};
    
    public NotificationQueueItem addToQueue(NotificationQueueItem item) {
        if (item.getRetryId() == null) {
            item.setRetryId(generateRetryId());
        }
        if (item.getRetryCount() == null) {
            item.setRetryCount(0);
        }
        if (item.getMaxRetryCount() == null) {
            item.setMaxRetryCount(MAX_RETRY_COUNT);
        }
        if (item.getStatus() == null) {
            item.setStatus("pending");
        }
        if (item.getNextRetryAt() == null) {
            item.setNextRetryAt(LocalDateTime.now());
        }
        if (item.getCreatedAt() == null) {
            item.setCreatedAt(LocalDateTime.now());
        }
        
        String dataKey = NOTIFICATION_PREFIX + item.getRetryId();
        redisTemplate.opsForValue().set(dataKey, JSONUtil.toJsonStr(item), 7, TimeUnit.DAYS);
        
        double score = item.getNextRetryAt().toEpochSecond(ZoneOffset.of("+8"));
        redisTemplate.opsForZSet().add(PENDING_QUEUE_KEY, item.getRetryId(), score);
        
        log.info("通知已加入Redis队列：retryId={}, orderId={}, nextRetryAt={}", 
                item.getRetryId(), item.getOrderId(), item.getNextRetryAt());
        
        return item;
    }
    
    public List<NotificationQueueItem> getReadyNotifications(int maxCount) {
        long now = LocalDateTime.now().toEpochSecond(ZoneOffset.of("+8"));
        
        Set<Object> readyIds = redisTemplate.opsForZSet()
                .rangeByScore(PENDING_QUEUE_KEY, 0, now, 0, maxCount);
        
        if (readyIds == null || readyIds.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<NotificationQueueItem> items = new ArrayList<>();
        
        for (Object idObj : readyIds) {
            String retryId = (String) idObj;
            
            String dataKey = NOTIFICATION_PREFIX + retryId;
            Object data = redisTemplate.opsForValue().get(dataKey);
            
            if (data != null) {
                try {
                    NotificationQueueItem item = JSONUtil.toBean((String) data, NotificationQueueItem.class);
                    
                    redisTemplate.opsForSet().add(PROCESSING_SET_KEY, retryId);
                    redisTemplate.opsForZSet().remove(PENDING_QUEUE_KEY, retryId);
                    
                    items.add(item);
                } catch (Exception e) {
                    log.error("解析通知数据失败：retryId={}", retryId, e);
                    redisTemplate.opsForZSet().remove(PENDING_QUEUE_KEY, retryId);
                    redisTemplate.delete(dataKey);
                }
            } else {
                redisTemplate.opsForZSet().remove(PENDING_QUEUE_KEY, retryId);
            }
        }
        
        log.info("从Redis获取到 {} 个待处理通知", items.size());
        return items;
    }
    
    public void markAsSuccess(String retryId) {
        String dataKey = NOTIFICATION_PREFIX + retryId;
        
        Object data = redisTemplate.opsForValue().get(dataKey);
        if (data != null) {
            try {
                NotificationQueueItem item = JSONUtil.toBean((String) data, NotificationQueueItem.class);
                item.setStatus("success");
                item.setLastNotifyAt(LocalDateTime.now());
                redisTemplate.opsForValue().set(dataKey, JSONUtil.toJsonStr(item), 7, TimeUnit.DAYS);
            } catch (Exception e) {
                log.error("更新通知状态失败：retryId={}", retryId, e);
            }
        }
        
        redisTemplate.opsForSet().remove(PROCESSING_SET_KEY, retryId);
        
        log.info("通知标记为成功：retryId={}", retryId);
    }
    
    public void markForRetry(NotificationQueueItem item, String errorMsg) {
        int newRetryCount = item.getRetryCount() + 1;
        item.setRetryCount(newRetryCount);
        item.setLastErrorMsg(errorMsg);
        item.setLastNotifyAt(LocalDateTime.now());
        
        if (newRetryCount >= item.getMaxRetryCount()) {
            item.setStatus("failed");
            
            String dataKey = NOTIFICATION_PREFIX + item.getRetryId();
            redisTemplate.opsForValue().set(dataKey, JSONUtil.toJsonStr(item), 30, TimeUnit.DAYS);
            
            redisTemplate.opsForSet().add(FAILED_SET_KEY, item.getRetryId());
            redisTemplate.opsForSet().remove(PROCESSING_SET_KEY, item.getRetryId());
            
            log.error("通知重试已达最大次数，标记为失败：retryId={}, maxRetryCount={}", 
                    item.getRetryId(), item.getMaxRetryCount());
        } else {
            int intervalMinutes = RETRY_INTERVALS[Math.min(newRetryCount - 1, RETRY_INTERVALS.length - 1)];
            item.setNextRetryAt(LocalDateTime.now().plusMinutes(intervalMinutes));
            item.setStatus("pending");
            
            String dataKey = NOTIFICATION_PREFIX + item.getRetryId();
            redisTemplate.opsForValue().set(dataKey, JSONUtil.toJsonStr(item), 7, TimeUnit.DAYS);
            
            double score = item.getNextRetryAt().toEpochSecond(ZoneOffset.of("+8"));
            redisTemplate.opsForZSet().add(PENDING_QUEUE_KEY, item.getRetryId(), score);
            
            redisTemplate.opsForSet().remove(PROCESSING_SET_KEY, item.getRetryId());
            
            log.info("通知安排下次重试：retryId={}, nextRetryAt={}", 
                    item.getRetryId(), item.getNextRetryAt());
        }
    }
    
    public NotificationQueueItem getByRetryId(String retryId) {
        String dataKey = NOTIFICATION_PREFIX + retryId;
        Object data = redisTemplate.opsForValue().get(dataKey);
        
        if (data == null) {
            return null;
        }
        
        try {
            return JSONUtil.toBean((String) data, NotificationQueueItem.class);
        } catch (Exception e) {
            log.error("解析通知数据失败：retryId={}", retryId, e);
            return null;
        }
    }
    
    public List<NotificationQueueItem> getFailedNotifications() {
        Set<Object> failedIds = redisTemplate.opsForSet().members(FAILED_SET_KEY);
        
        if (failedIds == null || failedIds.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<NotificationQueueItem> items = new ArrayList<>();
        
        for (Object idObj : failedIds) {
            String retryId = (String) idObj;
            NotificationQueueItem item = getByRetryId(retryId);
            if (item != null) {
                items.add(item);
            }
        }
        
        return items;
    }
    
    public List<NotificationQueueItem> getPendingNotifications() {
        Set<Object> pendingIds = redisTemplate.opsForZSet().range(PENDING_QUEUE_KEY, 0, -1);
        
        if (pendingIds == null || pendingIds.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<NotificationQueueItem> items = new ArrayList<>();
        
        for (Object idObj : pendingIds) {
            String retryId = (String) idObj;
            NotificationQueueItem item = getByRetryId(retryId);
            if (item != null) {
                items.add(item);
            }
        }
        
        return items;
    }
    
    public boolean manualRetry(String retryId) {
        NotificationQueueItem item = getByRetryId(retryId);
        if (item == null) {
            throw new IllegalArgumentException("重试记录不存在：" + retryId);
        }
        
        item.setRetryCount(0);
        item.setStatus("pending");
        item.setNextRetryAt(LocalDateTime.now());
        
        addToQueue(item);
        
        redisTemplate.opsForSet().remove(FAILED_SET_KEY, retryId);
        
        log.info("手动触发重试：retryId={}", retryId);
        return true;
    }
    
    public long getPendingCount() {
        Long count = redisTemplate.opsForZSet().zCard(PENDING_QUEUE_KEY);
        return count != null ? count : 0;
    }
    
    public long getFailedCount() {
        Long count = redisTemplate.opsForSet().size(FAILED_SET_KEY);
        return count != null ? count : 0;
    }
    
    public boolean existsByOrderId(String orderId) {
        Set<Object> pendingIds = redisTemplate.opsForZSet().range(PENDING_QUEUE_KEY, 0, -1);
        if (pendingIds != null) {
            for (Object idObj : pendingIds) {
                NotificationQueueItem item = getByRetryId((String) idObj);
                if (item != null && orderId.equals(item.getOrderId())) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private String generateRetryId() {
        return "RET" + System.currentTimeMillis() + IdUtil.randomUUID().substring(0, 6).toUpperCase();
    }
}
