package com.example.mailservice.service;

import com.example.mailservice.config.AppConfig;
import com.example.mailservice.model.IndexTask;
import com.example.mailservice.model.SendTask;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisQueueService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final AppConfig appConfig;
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    public void pushSendTask(SendTask task) {
        try {
            String json = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().rightPush(
                    appConfig.getRedisQueue().getSendQueueKey(),
                    json
            );
            log.info("发送任务已入队，mailId: {}, taskId: {}", task.getMailId(), task.getTaskId());
        } catch (JsonProcessingException e) {
            log.error("发送任务序列化失败，taskId: {}", task.getTaskId(), e);
        }
    }

    public SendTask pollSendTask() {
        String json = (String) redisTemplate.opsForList().leftPop(
                appConfig.getRedisQueue().getSendQueueKey(),
                appConfig.getRedisQueue().getPollTimeout(),
                TimeUnit.MILLISECONDS
        );
        if (json == null) {
            return null;
        }
        try {
            SendTask task = objectMapper.readValue(json, SendTask.class);
            redisTemplate.opsForHash().put(
                    appConfig.getRedisQueue().getSendProcessingKey(),
                    task.getTaskId(),
                    json
            );
            return task;
        } catch (JsonProcessingException e) {
            log.error("发送任务反序列化失败", e);
            return null;
        }
    }

    public void markSendTaskCompleted(String taskId) {
        redisTemplate.opsForHash().delete(
                appConfig.getRedisQueue().getSendProcessingKey(),
                taskId
        );
        log.debug("发送任务标记完成，taskId: {}", taskId);
    }

    public void markSendTaskFailed(SendTask task, boolean retry) {
        String taskId = task.getTaskId();
        redisTemplate.opsForHash().delete(
                appConfig.getRedisQueue().getSendProcessingKey(),
                taskId
        );

        if (retry && task.canRetry()) {
            task.incrementRetry();
            try {
                String json = objectMapper.writeValueAsString(task);
                redisTemplate.opsForList().rightPush(
                        appConfig.getRedisQueue().getSendQueueKey(),
                        json
                );
                log.info("发送任务重新入队，taskId: {}, 重试次数: {}", taskId, task.getRetryCount());
            } catch (JsonProcessingException e) {
                log.error("发送任务重新序列化失败", e);
                moveToSendDeadLetter(task);
            }
        } else {
            moveToSendDeadLetter(task);
            log.warn("发送任务移入死信队列，taskId: {}", taskId);
        }
    }

    private void moveToSendDeadLetter(SendTask task) {
        try {
            String json = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().rightPush(
                    appConfig.getRedisQueue().getSendDeadLetterKey(),
                    json
            );
        } catch (JsonProcessingException e) {
            log.error("死信队列写入失败", e);
        }
    }

    public void pushIndexTask(IndexTask task) {
        try {
            String json = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().rightPush(
                    appConfig.getRedisQueue().getIndexQueueKey(),
                    json
            );
            log.info("索引任务已入队，mailId: {}, taskId: {}", task.getMailId(), task.getTaskId());
        } catch (JsonProcessingException e) {
            log.error("索引任务序列化失败，taskId: {}", task.getTaskId(), e);
        }
    }

    public IndexTask pollIndexTask() {
        String json = (String) redisTemplate.opsForList().leftPop(
                appConfig.getRedisQueue().getIndexQueueKey(),
                appConfig.getRedisQueue().getPollTimeout(),
                TimeUnit.MILLISECONDS
        );
        if (json == null) {
            return null;
        }
        try {
            IndexTask task = objectMapper.readValue(json, IndexTask.class);
            redisTemplate.opsForHash().put(
                    appConfig.getRedisQueue().getIndexProcessingKey(),
                    task.getTaskId(),
                    json
            );
            return task;
        } catch (JsonProcessingException e) {
            log.error("索引任务反序列化失败", e);
            return null;
        }
    }

    public void markIndexTaskCompleted(String taskId) {
        redisTemplate.opsForHash().delete(
                appConfig.getRedisQueue().getIndexProcessingKey(),
                taskId
        );
        log.debug("索引任务标记完成，taskId: {}", taskId);
    }

    public void markIndexTaskFailed(IndexTask task, boolean retry) {
        String taskId = task.getTaskId();
        redisTemplate.opsForHash().delete(
                appConfig.getRedisQueue().getIndexProcessingKey(),
                taskId
        );

        if (retry && task.canRetry()) {
            task.incrementRetry();
            try {
                String json = objectMapper.writeValueAsString(task);
                redisTemplate.opsForList().rightPush(
                        appConfig.getRedisQueue().getIndexQueueKey(),
                        json
                );
                log.info("索引任务重新入队，taskId: {}, 重试次数: {}", taskId, task.getRetryCount());
            } catch (JsonProcessingException e) {
                log.error("索引任务重新序列化失败", e);
                moveToIndexDeadLetter(task);
            }
        } else {
            moveToIndexDeadLetter(task);
            log.warn("索引任务移入死信队列，taskId: {}", taskId);
        }
    }

    private void moveToIndexDeadLetter(IndexTask task) {
        try {
            String json = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().rightPush(
                    appConfig.getRedisQueue().getIndexDeadLetterKey(),
                    json
            );
        } catch (JsonProcessingException e) {
            log.error("死信队列写入失败", e);
        }
    }

    public long getSendQueueSize() {
        Long size = redisTemplate.opsForList().size(appConfig.getRedisQueue().getSendQueueKey());
        return size != null ? size : 0;
    }

    public long getIndexQueueSize() {
        Long size = redisTemplate.opsForList().size(appConfig.getRedisQueue().getIndexQueueKey());
        return size != null ? size : 0;
    }

    public long getSendDeadLetterSize() {
        Long size = redisTemplate.opsForList().size(appConfig.getRedisQueue().getSendDeadLetterKey());
        return size != null ? size : 0;
    }

    public long getIndexDeadLetterSize() {
        Long size = redisTemplate.opsForList().size(appConfig.getRedisQueue().getIndexDeadLetterKey());
        return size != null ? size : 0;
    }

    public void recoverProcessingTasks() {
        String sendProcessingKey = appConfig.getRedisQueue().getSendProcessingKey();
        String indexProcessingKey = appConfig.getRedisQueue().getIndexProcessingKey();

        redisTemplate.opsForHash().keys(sendProcessingKey).forEach(key -> {
            String json = (String) redisTemplate.opsForHash().get(sendProcessingKey, key);
            if (json != null) {
                redisTemplate.opsForList().rightPush(appConfig.getRedisQueue().getSendQueueKey(), json);
                redisTemplate.opsForHash().delete(sendProcessingKey, key);
                log.info("恢复发送任务到队列，taskId: {}", key);
            }
        });

        redisTemplate.opsForHash().keys(indexProcessingKey).forEach(key -> {
            String json = (String) redisTemplate.opsForHash().get(indexProcessingKey, key);
            if (json != null) {
                redisTemplate.opsForList().rightPush(appConfig.getRedisQueue().getIndexQueueKey(), json);
                redisTemplate.opsForHash().delete(indexProcessingKey, key);
                log.info("恢复索引任务到队列，taskId: {}", key);
            }
        });
    }
}
