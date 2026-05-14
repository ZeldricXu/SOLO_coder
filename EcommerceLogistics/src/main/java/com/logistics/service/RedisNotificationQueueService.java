package com.logistics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.logistics.config.NotificationConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.redis.host")
public class RedisNotificationQueueService {

    private final StringRedisTemplate redisTemplate;
    private final NotificationConfig notificationConfig;

    private final ObjectMapper objectMapper = createObjectMapper();

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    public boolean enqueueNotification(Object task) {
        try {
            String json = objectMapper.writeValueAsString(task);
            Long result = redisTemplate.opsForList()
                    .rightPush(notificationConfig.getRedisQueueName(), json);
            log.debug("通知入队Redis: {} 结果: {}", notificationConfig.getRedisQueueName(), result);
            return result != null && result > 0;
        } catch (JsonProcessingException e) {
            log.error("序列化通知任务失败", e);
            return false;
        }
    }

    public <T> T dequeueNotification(Class<T> clazz) {
        String json = redisTemplate.opsForList()
                .leftPop(notificationConfig.getRedisQueueName(), 5, TimeUnit.SECONDS);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("反序列化通知任务失败", e);
            return null;
        }
    }

    public <T> T dequeueNotificationNonBlocking(Class<T> clazz) {
        String json = redisTemplate.opsForList()
                .leftPop(notificationConfig.getRedisQueueName());
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("反序列化通知任务失败", e);
            return null;
        }
    }

    public boolean enqueueFailedNotification(Object task) {
        try {
            String json = objectMapper.writeValueAsString(task);
            Long result = redisTemplate.opsForList()
                    .rightPush(notificationConfig.getRedisFailedQueueName(), json);
            log.debug("失败通知入队Redis: {} 结果: {}", notificationConfig.getRedisFailedQueueName(), result);
            return result != null && result > 0;
        } catch (JsonProcessingException e) {
            log.error("序列化失败通知任务失败", e);
            return false;
        }
    }

    public <T> T dequeueFailedNotification(Class<T> clazz) {
        String json = redisTemplate.opsForList()
                .leftPop(notificationConfig.getRedisFailedQueueName());
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("反序列化失败通知任务失败", e);
            return null;
        }
    }

    public <T> List<T> getAllFailedNotifications(Class<T> clazz) {
        List<T> result = new ArrayList<>();
        List<String> jsons = redisTemplate.opsForList()
                .range(notificationConfig.getRedisFailedQueueName(), 0, -1);
        if (jsons == null || jsons.isEmpty()) {
            return result;
        }

        for (String json : jsons) {
            try {
                result.add(objectMapper.readValue(json, clazz));
            } catch (JsonProcessingException e) {
                log.error("反序列化失败通知任务失败", e);
            }
        }
        return result;
    }

    public void clearFailedQueue() {
        redisTemplate.delete(notificationConfig.getRedisFailedQueueName());
        log.info("清空Redis失败通知队列");
    }

    public long getQueueSize() {
        Long size = redisTemplate.opsForList().size(notificationConfig.getRedisQueueName());
        return size != null ? size : 0;
    }

    public long getFailedQueueSize() {
        Long size = redisTemplate.opsForList().size(notificationConfig.getRedisFailedQueueName());
        return size != null ? size : 0;
    }

    public boolean isRedisAvailable() {
        try {
            String ping = redisTemplate.getConnectionFactory().getConnection().ping();
            return "PONG".equals(ping);
        } catch (Exception e) {
            return false;
        }
    }
}
