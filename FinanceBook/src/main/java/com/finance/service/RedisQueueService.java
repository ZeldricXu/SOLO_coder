package com.finance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisQueueService {

    public static final String DEFAULT_QUEUE_KEY = "finance:category_match:queue";
    public static final String PROCESSING_KEY_PREFIX = "finance:category_match:processing:";
    public static final String DLQ_KEY = "finance:category_match:dlq";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private final Map<String, Queue<String>> fallbackQueues = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fallbackProcessing = new ConcurrentHashMap<>();
    private final Queue<String> fallbackDlq = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean redisAvailable = new AtomicBoolean(true);

    @PostConstruct
    public void init() {
        try {
            stringRedisTemplate.hasKey("test");
            redisAvailable.set(true);
            log.info("Redis连接正常，使用Redis队列");
        } catch (Exception e) {
            redisAvailable.set(false);
            log.warn("Redis不可用，使用内存队列作为后备方案");
        }
    }

    public String pushToQueue(String queueKey, String message) {
        String messageId = UUID.randomUUID().toString();
        Map<String, Object> messageWrapper = new HashMap<>();
        messageWrapper.put("id", messageId);
        messageWrapper.put("content", message);
        messageWrapper.put("timestamp", System.currentTimeMillis());

        try {
            String wrappedMessage = objectMapper.writeValueAsString(messageWrapper);

            if (redisAvailable.get()) {
                try {
                    stringRedisTemplate.opsForList().rightPush(queueKey, wrappedMessage);
                    log.debug("消息已推送到Redis队列: queueKey={}, messageId={}", queueKey, messageId);
                    return messageId;
                } catch (Exception e) {
                    log.warn("Redis推送失败，切换到内存队列: {}", e.getMessage());
                    redisAvailable.set(false);
                }
            }

            fallbackQueues.computeIfAbsent(queueKey, k -> new ConcurrentLinkedQueue<>())
                    .offer(wrappedMessage);
            log.debug("消息已推送到内存队列: queueKey={}, messageId={}", queueKey, messageId);
            return messageId;

        } catch (JsonProcessingException e) {
            log.error("包装消息失败", e);
            throw new RuntimeException("包装消息失败", e);
        }
    }

    public String popFromQueue(String queueKey, long timeoutSeconds) {
        if (redisAvailable.get()) {
            try {
                String message = stringRedisTemplate.opsForList()
                        .leftPop(queueKey, Duration.ofSeconds(timeoutSeconds));
                if (message != null) {
                    log.debug("从Redis队列获取消息: queueKey={}", queueKey);
                    return unwrapMessage(message);
                }
            } catch (Exception e) {
                log.warn("Redis读取失败，切换到内存队列: {}", e.getMessage());
                redisAvailable.set(false);
            }
        }

        Queue<String> fallbackQueue = fallbackQueues.get(queueKey);
        if (fallbackQueue != null) {
            String message = fallbackQueue.poll();
            if (message != null) {
                log.debug("从内存队列获取消息: queueKey={}", queueKey);
                return unwrapMessage(message);
            }
        }

        return null;
    }

    public String popFromQueue(String queueKey) {
        return popFromQueue(queueKey, 5);
    }

    public long getQueueSize(String queueKey) {
        if (redisAvailable.get()) {
            try {
                Long size = stringRedisTemplate.opsForList().size(queueKey);
                return size != null ? size : 0;
            } catch (Exception e) {
                log.warn("Redis查询失败", e);
            }
        }

        Queue<String> fallbackQueue = fallbackQueues.get(queueKey);
        return fallbackQueue != null ? fallbackQueue.size() : 0;
    }

    public boolean markProcessing(String messageId, String queueKey) {
        String processingKey = PROCESSING_KEY_PREFIX + queueKey + ":" + messageId;

        if (redisAvailable.get()) {
            try {
                stringRedisTemplate.opsForValue().set(processingKey, "processing", Duration.ofMinutes(30));
                return true;
            } catch (Exception e) {
                log.warn("Redis标记处理中失败", e);
            }
        }

        fallbackProcessing.computeIfAbsent(queueKey, k -> ConcurrentHashMap.newKeySet()).add(messageId);
        return true;
    }

    public boolean ackMessage(String messageId, String queueKey) {
        String processingKey = PROCESSING_KEY_PREFIX + queueKey + ":" + messageId;

        if (redisAvailable.get()) {
            try {
                stringRedisTemplate.delete(processingKey);
                return true;
            } catch (Exception e) {
                log.warn("Redis确认消息失败", e);
            }
        }

        Set<String> processingSet = fallbackProcessing.get(queueKey);
        if (processingSet != null) {
            processingSet.remove(messageId);
        }
        return true;
    }

    public boolean nackMessage(String message, String queueKey) {
        if (redisAvailable.get()) {
            try {
                stringRedisTemplate.opsForList().rightPush(DLQ_KEY, message);
                return true;
            } catch (Exception e) {
                log.warn("Redis死信队列写入失败", e);
            }
        }

        fallbackDlq.offer(message);
        return true;
    }

    private String unwrapMessage(String wrappedMessage) {
        try {
            Map<String, Object> wrapper = objectMapper.readValue(wrappedMessage, Map.class);
            return (String) wrapper.get("content");
        } catch (Exception e) {
            log.debug("消息不是包装格式，直接返回");
            return wrappedMessage;
        }
    }

    public boolean isRedisAvailable() {
        return redisAvailable.get();
    }

    public long getDlqSize() {
        if (redisAvailable.get()) {
            try {
                Long size = stringRedisTemplate.opsForList().size(DLQ_KEY);
                return size != null ? size : 0;
            } catch (Exception e) {
                log.warn("Redis死信队列查询失败", e);
            }
        }
        return fallbackDlq.size();
    }

    public List<String> recoverFromDlq() {
        List<String> recovered = new ArrayList<>();

        if (redisAvailable.get()) {
            try {
                String message;
                while ((message = stringRedisTemplate.opsForList().leftPop(DLQ_KEY)) != null) {
                    recovered.add(message);
                    stringRedisTemplate.opsForList().rightPush(DEFAULT_QUEUE_KEY, message);
                }
            } catch (Exception e) {
                log.warn("从Redis死信队列恢复失败", e);
            }
        } else {
            String message;
            while ((message = fallbackDlq.poll()) != null) {
                recovered.add(message);
                fallbackQueues.computeIfAbsent(DEFAULT_QUEUE_KEY, k -> new ConcurrentLinkedQueue<>())
                        .offer(message);
            }
        }

        log.info("从死信队列恢复消息: count={}", recovered.size());
        return recovered;
    }

    public void checkRedisConnection() {
        try {
            stringRedisTemplate.hasKey("health_check");
            if (!redisAvailable.get()) {
                redisAvailable.set(true);
                log.info("Redis连接已恢复");
                migrateFallbackToRedis();
            }
        } catch (Exception e) {
            if (redisAvailable.get()) {
                redisAvailable.set(false);
                log.warn("Redis连接断开，已切换到内存队列");
            }
        }
    }

    private void migrateFallbackToRedis() {
        for (Map.Entry<String, Queue<String>> entry : fallbackQueues.entrySet()) {
            String queueKey = entry.getKey();
            Queue<String> queue = entry.getValue();

            String message;
            while ((message = queue.poll()) != null) {
                stringRedisTemplate.opsForList().rightPush(queueKey, message);
            }
        }

        log.info("内存队列数据已迁移到Redis");
    }
}
