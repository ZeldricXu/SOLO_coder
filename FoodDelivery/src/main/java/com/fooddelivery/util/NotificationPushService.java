package com.fooddelivery.util;

import com.fooddelivery.config.PushConfigProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class NotificationPushService {

    @Autowired
    private PushConfigProperties pushConfig;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${fooddelivery.redis.queue:fooddelivery:notification:queue}")
    private String notificationQueue;

    @Value("${fooddelivery.redis.offline-prefix:fooddelivery:offline:}")
    private String offlinePrefix;

    public enum PushStrategy {
        REAL_TIME,
        BATCH
    }

    public enum UserStatus {
        ONLINE,
        OFFLINE
    }

    public static class PushMessage implements Serializable {
        private static final long serialVersionUID = 1L;
        private String messageId;
        private String userId;
        private String orderId;
        private String status;
        private String message;
        private PushStrategy strategy;
        private boolean isImportant;
        private long timestamp;

        public PushMessage() {
            this.timestamp = System.currentTimeMillis();
        }

        public PushMessage(String userId, String orderId, String status, String message, PushStrategy strategy, boolean isImportant) {
            this.messageId = UUID.randomUUID().toString();
            this.userId = userId;
            this.orderId = orderId;
            this.status = status;
            this.message = message;
            this.strategy = strategy;
            this.isImportant = isImportant;
            this.timestamp = System.currentTimeMillis();
        }

        public String getMessageId() {
            return messageId;
        }

        public String getUserId() {
            return userId;
        }

        public String getOrderId() {
            return orderId;
        }

        public String getStatus() {
            return status;
        }

        public String getMessage() {
            return message;
        }

        public PushStrategy getStrategy() {
            return strategy;
        }

        public boolean isImportant() {
            return isImportant;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public void setStrategy(PushStrategy strategy) {
            this.strategy = strategy;
        }

        public void setImportant(boolean important) {
            isImportant = important;
        }
    }

    private final ConcurrentHashMap<String, UserStatus> userStatusMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<PushMessage>> batchQueues = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (pushConfig.getStrategies().isEmpty()) {
            addDefaultStrategies();
        }
    }

    private void addDefaultStrategies() {
        PushConfigProperties.PushStrategyConfig realtimeConfig = new PushConfigProperties.PushStrategyConfig();
        realtimeConfig.setStrategy("realtime");
        realtimeConfig.setImportant(true);
        realtimeConfig.setDescription("重要状态");
        pushConfig.getStrategies().put("pending_pickup", realtimeConfig);
        pushConfig.getStrategies().put("picked_up", realtimeConfig);
        pushConfig.getStrategies().put("delivered", realtimeConfig);
        pushConfig.getStrategies().put("cancelled", realtimeConfig);
        pushConfig.getStrategies().put("confirmed", realtimeConfig);

        PushConfigProperties.PushStrategyConfig batchConfig = new PushConfigProperties.PushStrategyConfig();
        batchConfig.setStrategy("batch");
        batchConfig.setImportant(false);
        batchConfig.setDescription("普通状态");
        pushConfig.getStrategies().put("pending_confirm", batchConfig);
        pushConfig.getStrategies().put("delivering", batchConfig);
        pushConfig.getStrategies().put("reviewed", batchConfig);
    }

    public void setUserOnline(String userId) {
        userStatusMap.put(userId, UserStatus.ONLINE);
    }

    public void setUserOffline(String userId) {
        userStatusMap.put(userId, UserStatus.OFFLINE);
    }

    public UserStatus getUserStatus(String userId) {
        return userStatusMap.getOrDefault(userId, UserStatus.OFFLINE);
    }

    public PushStrategy getPushStrategy(String statusType) {
        String strategyStr = pushConfig.getPushStrategy(statusType);
        return "realtime".equalsIgnoreCase(strategyStr) ? PushStrategy.REAL_TIME : PushStrategy.BATCH;
    }

    public boolean isImportantStatus(String statusType) {
        return pushConfig.isImportantStatus(statusType);
    }

    public int getBatchThreshold() {
        return pushConfig.getBatchThreshold();
    }

    public boolean pushNotification(String userId, String orderId, String statusType, String message) {
        PushStrategy strategy = getPushStrategy(statusType);
        boolean isImportant = isImportantStatus(statusType);
        PushMessage pushMessage = new PushMessage(userId, orderId, statusType, message, strategy, isImportant);
        return pushNotification(userId, pushMessage);
    }

    public boolean pushNotification(String userId, PushMessage message) {
        UserStatus status = getUserStatus(userId);
        if (message.isImportant()) {
            return pushImportant(userId, message, status);
        } else {
            return pushNormal(userId, message, status);
        }
    }

    private boolean pushImportant(String userId, PushMessage message, UserStatus status) {
        if (status == UserStatus.ONLINE) {
            return enqueueToRedis(message);
        } else {
            return storeOffline(userId, message);
        }
    }

    private boolean pushNormal(String userId, PushMessage message, UserStatus status) {
        if (status == UserStatus.ONLINE) {
            addToBatchQueue(userId, message);
            int batchSize = getBatchQueueSize(userId);
            if (batchSize >= getBatchThreshold()) {
                flushBatch(userId);
            }
            return true;
        } else {
            return storeOffline(userId, message);
        }
    }

    private void addToBatchQueue(String userId, PushMessage message) {
        batchQueues.computeIfAbsent(userId, k -> new ArrayList<>()).add(message);
    }

    private int getBatchQueueSize(String userId) {
        List<PushMessage> queue = batchQueues.get(userId);
        return queue != null ? queue.size() : 0;
    }

    private boolean enqueueToRedis(PushMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.opsForList().rightPush(notificationQueue, json);
            log.debug("消息已入队Redis: {}", message.getMessageId());
            return true;
        } catch (Exception e) {
            log.error("消息入队Redis失败: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean storeOffline(String userId, PushMessage message) {
        try {
            String key = offlinePrefix + userId;
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.expire(key, 7, TimeUnit.DAYS);
            log.debug("离线消息已存储: userId={}, messageId={}", userId, message.getMessageId());
            return true;
        } catch (Exception e) {
            log.error("离线消息存储失败: {}", e.getMessage(), e);
            return false;
        }
    }

    public int flushBatch(String userId) {
        List<PushMessage> queue = batchQueues.remove(userId);
        if (queue == null || queue.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (PushMessage msg : queue) {
            if (enqueueToRedis(msg)) {
                count++;
            }
        }
        log.debug("批量推送完成: userId={}, count={}", userId, count);
        return count;
    }

    public int flushAllBatches() {
        int total = 0;
        Set<String> userIds = new HashSet<>(batchQueues.keySet());
        for (String userId : userIds) {
            total += flushBatch(userId);
        }
        return total;
    }

    public List<PushMessage> getOfflineMessages(String userId) {
        List<PushMessage> messages = new ArrayList<>();
        try {
            String key = offlinePrefix + userId;
            List<String> jsonList = redisTemplate.opsForList().range(key, 0, -1);
            if (jsonList != null) {
                for (String json : jsonList) {
                    PushMessage msg = objectMapper.readValue(json, PushMessage.class);
                    messages.add(msg);
                }
            }
        } catch (Exception e) {
            log.error("获取离线消息失败: {}", e.getMessage(), e);
        }
        return messages;
    }

    public int clearOfflineMessages(String userId) {
        try {
            String key = offlinePrefix + userId;
            Long size = redisTemplate.opsForList().size(key);
            redisTemplate.delete(key);
            return size != null ? size.intValue() : 0;
        } catch (Exception e) {
            log.error("清除离线消息失败: {}", e.getMessage(), e);
            return 0;
        }
    }

    public List<PushMessage> getBatchQueue(String userId) {
        List<PushMessage> queue = batchQueues.get(userId);
        return queue != null ? new ArrayList<>(queue) : new ArrayList<>();
    }

    public void clearAll() {
        batchQueues.clear();
        userStatusMap.clear();
    }

    public int getBatchQueueCount(String userId) {
        return getBatchQueueSize(userId);
    }

    public int getTotalBatchQueueCount() {
        return batchQueues.values().stream().mapToInt(List::size).sum();
    }

    public long getRedisQueueSize() {
        try {
            Long size = redisTemplate.opsForList().size(notificationQueue);
            return size != null ? size : 0;
        } catch (Exception e) {
            log.error("获取Redis队列大小失败: {}", e.getMessage());
            return 0;
        }
    }

    public PushMessage dequeueFromRedis() {
        try {
            String json = redisTemplate.opsForList().leftPop(notificationQueue);
            if (json != null) {
                return objectMapper.readValue(json, PushMessage.class);
            }
        } catch (Exception e) {
            log.error("从Redis出队失败: {}", e.getMessage(), e);
        }
        return null;
    }
}
