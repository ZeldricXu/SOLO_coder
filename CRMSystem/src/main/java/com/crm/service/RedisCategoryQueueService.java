package com.crm.service;

import com.crm.config.CategoryQueueProperties;
import com.crm.entity.CategoryTask;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class RedisCategoryQueueService {

    private final StringRedisTemplate redisTemplate;
    private final CategoryQueueProperties properties;
    private final ObjectMapper objectMapper;

    public RedisCategoryQueueService(StringRedisTemplate redisTemplate, CategoryQueueProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public boolean addTask(String customerId, String customerValue) {
        String taskId = UUID.randomUUID().toString();
        CategoryTask task = CategoryTask.builder()
                .taskId(taskId)
                .customerId(customerId)
                .customerValue(customerValue)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .status("PENDING")
                .build();

        try {
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().leftPush(properties.getQueueName(), taskJson);
            log.debug("分类任务已加入Redis队列: taskId={}, customerId={}", taskId, customerId);
            return true;
        } catch (JsonProcessingException e) {
            log.error("序列化分类任务失败: customerId={}", customerId, e);
            return false;
        }
    }

    public CategoryTask pollTask() {
        String taskJson = redisTemplate.opsForList().rightPop(
                properties.getQueueName(),
                properties.getPollIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        if (taskJson == null) {
            return null;
        }

        try {
            CategoryTask task = objectMapper.readValue(taskJson, CategoryTask.class);
            String processingKey = properties.getProcessingQueueName() + ":" + task.getTaskId();
            redisTemplate.opsForValue().set(processingKey, taskJson, properties.getProcessingTimeoutMs(), TimeUnit.MILLISECONDS);
            return task;
        } catch (JsonProcessingException e) {
            log.error("反序列化任务失败: {}", taskJson, e);
            return null;
        }
    }

    public boolean markTaskSuccess(CategoryTask task) {
        String processingKey = properties.getProcessingQueueName() + ":" + task.getTaskId();
        redisTemplate.delete(processingKey);
        task.setStatus("SUCCESS");
        log.debug("分类任务成功: taskId={}, customerId={}", task.getTaskId(), task.getCustomerId());
        return true;
    }

    public boolean markTaskFailed(CategoryTask task, String errorMessage) {
        task.setLastRetryAt(LocalDateTime.now());
        task.setRetryCount(task.getRetryCount() + 1);
        task.setErrorMessage(errorMessage);

        if (task.getRetryCount() >= properties.getMaxRetries()) {
            String processingKey = properties.getProcessingQueueName() + ":" + task.getTaskId();
            redisTemplate.delete(processingKey);
            
            try {
                task.setStatus("FAILED");
                String taskJson = objectMapper.writeValueAsString(task);
                redisTemplate.opsForList().leftPush(properties.getFailedQueueName(), taskJson);
                log.error("分类任务失败已达到最大重试次数，加入失败队列: taskId={}, customerId={}, error={}",
                        task.getTaskId(), task.getCustomerId(), errorMessage);
            } catch (JsonProcessingException e) {
                log.error("序列化失败任务失败: taskId={}", task.getTaskId(), e);
            }
            return false;
        } else {
            try {
                String processingKey = properties.getProcessingQueueName() + ":" + task.getTaskId();
                redisTemplate.delete(processingKey);
                
                task.setStatus("PENDING");
                String taskJson = objectMapper.writeValueAsString(task);
                redisTemplate.opsForList().leftPush(properties.getQueueName(), taskJson);
                log.warn("分类任务失败，准备重试: taskId={}, retryCount={}, error={}",
                        task.getTaskId(), task.getRetryCount(), errorMessage);
                return true;
            } catch (JsonProcessingException e) {
                log.error("序列化重试任务失败: taskId={}", task.getTaskId(), e);
                return false;
            }
        }
    }

    public long getPendingTaskCount() {
        Long count = redisTemplate.opsForList().size(properties.getQueueName());
        return count != null ? count : 0;
    }

    public long getProcessingTaskCount() {
        String pattern = properties.getProcessingQueueName() + ":*";
        Long count = redisTemplate.keys(pattern).stream().count();
        return count != null ? count : 0;
    }

    public long getFailedTaskCount() {
        Long count = redisTemplate.opsForList().size(properties.getFailedQueueName());
        return count != null ? count : 0;
    }

    public boolean requeueFailedTasks() {
        String taskJson;
        int requeued = 0;
        while ((taskJson = redisTemplate.opsForList().rightPop(properties.getFailedQueueName())) != null) {
            try {
                CategoryTask task = objectMapper.readValue(taskJson, CategoryTask.class);
                task.setRetryCount(0);
                task.setStatus("PENDING");
                task.setErrorMessage(null);
                String newTaskJson = objectMapper.writeValueAsString(task);
                redisTemplate.opsForList().leftPush(properties.getQueueName(), newTaskJson);
                requeued++;
            } catch (JsonProcessingException e) {
                log.error("重新入队失败任务出错: {}", taskJson, e);
            }
        }
        log.info("重新入队失败任务: count={}", requeued);
        return true;
    }
}
