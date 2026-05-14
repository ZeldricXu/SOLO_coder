package com.reviewsystem.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class RedisQueueService {

    private static final Logger log = LoggerFactory.getLogger(RedisQueueService.class);

    @Resource
    private RedisTemplate<String, String> stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    public <T> boolean pushTask(String queueName, T task) {
        try {
            String json = objectMapper.writeValueAsString(task);
            stringRedisTemplate.opsForList().rightPush(queueName, json);
            log.debug("任务已入队: {} -> {}", queueName, json);
            return true;
        } catch (JsonProcessingException e) {
            log.error("任务序列化失败: {}", queueName, e);
            return false;
        }
    }

    public <T> T popTask(String queueName, Class<T> clazz) {
        String json = stringRedisTemplate.opsForList().leftPop(queueName);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("任务反序列化失败: {} -> {}", queueName, json, e);
            return null;
        }
    }

    public <T> T popTaskBlocking(String queueName, long timeout, TimeUnit unit, Class<T> clazz) {
        String json = stringRedisTemplate.opsForList().leftPop(queueName, timeout, unit);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("任务反序列化失败: {} -> {}", queueName, json, e);
            return null;
        }
    }

    public <T> List<T> popTasksBatch(String queueName, int batchSize, Class<T> clazz) {
        List<T> tasks = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            T task = popTask(queueName, clazz);
            if (task == null) {
                break;
            }
            tasks.add(task);
        }
        return tasks;
    }

    public long getQueueSize(String queueName) {
        Long size = stringRedisTemplate.opsForList().size(queueName);
        return size != null ? size : 0;
    }

    public boolean isQueueEmpty(String queueName) {
        return getQueueSize(queueName) == 0;
    }

    public void clearQueue(String queueName) {
        stringRedisTemplate.delete(queueName);
        log.info("队列已清空: {}", queueName);
    }

    public <T> boolean pushToProcessingQueue(String queueName, T task) {
        String processingQueue = queueName + ":processing";
        return pushTask(processingQueue, task);
    }

    public <T> boolean removeFromProcessingQueue(String queueName, T task) {
        String processingQueue = queueName + ":processing";
        try {
            String json = objectMapper.writeValueAsString(task);
            Long removed = stringRedisTemplate.opsForList().remove(processingQueue, 1, json);
            return removed != null && removed > 0;
        } catch (JsonProcessingException e) {
            log.error("处理队列任务移除失败: {}", queueName, e);
            return false;
        }
    }

    public <T> void moveToDeadQueue(String queueName, T task, String errorMessage) {
        String deadQueue = queueName + ":dead";
        try {
            String json = objectMapper.writeValueAsString(task);
            stringRedisTemplate.opsForList().rightPush(deadQueue, json);
            log.warn("任务移入死信队列: {}, 错误: {}", queueName, errorMessage);
        } catch (JsonProcessingException e) {
            log.error("死信队列任务序列化失败: {}", queueName, e);
        }
    }
}
