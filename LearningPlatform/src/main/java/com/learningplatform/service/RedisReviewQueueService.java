
package com.learningplatform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.learningplatform.config.ReviewQueueConfig;
import com.learningplatform.dto.ReviewTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class RedisReviewQueueService {

    private static final Logger logger = LoggerFactory.getLogger(RedisReviewQueueService.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ReviewQueueConfig queueConfig;

    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    public String enqueueTask(String courseId, String studentId, Integer rating, String content) {
        String taskId = generateTaskId();
        ReviewTask task = ReviewTask.create(taskId, courseId, studentId, rating, content);
        
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            String taskKey = getTaskKey(taskId);
            
            redisTemplate.opsForValue().set(taskKey, taskJson, 24, TimeUnit.HOURS);
            redisTemplate.opsForList().rightPush(queueConfig.getQueueName(), taskId);
            
            logger.info("评价任务入队: task={}, course={}, student={}", taskId, courseId, studentId);
            return taskId;
        } catch (JsonProcessingException e) {
            logger.error("序列化评价任务失败: task={}", taskId, e);
            return null;
        }
    }

    public ReviewTask dequeueTask() {
        try {
            String taskId = (String) redisTemplate.opsForList().leftPop(queueConfig.getQueueName());
            if (taskId == null) {
                return null;
            }
            
            String taskKey = getTaskKey(taskId);
            String taskJson = (String) redisTemplate.opsForValue().get(taskKey);
            if (taskJson == null) {
                logger.warn("任务数据丢失: task={}", taskId);
                return null;
            }

            ReviewTask task = objectMapper.readValue(taskJson, ReviewTask.class);
            task.markProcessing();
            
            redisTemplate.opsForValue().set(taskKey, objectMapper.writeValueAsString(task), 24, TimeUnit.HOURS);
            redisTemplate.opsForList().rightPush(queueConfig.getProcessingQueueKey(), taskId);
            
            logger.debug("评价任务出队: task={}, course={}, student={}", 
                    taskId, task.getCourseId(), task.getStudentId());
            
            return task;
        } catch (JsonProcessingException e) {
            logger.error("反序列化评价任务失败", e);
            return null;
        }
    }

    public boolean completeTask(String taskId) {
        try {
            String taskKey = getTaskKey(taskId);
            String taskJson = (String) redisTemplate.opsForValue().get(taskKey);
            if (taskJson == null) {
                return false;
            }

            ReviewTask task = objectMapper.readValue(taskJson, ReviewTask.class);
            task.markCompleted();
            
            redisTemplate.opsForValue().set(taskKey, objectMapper.writeValueAsString(task), 1, TimeUnit.HOURS);
            redisTemplate.opsForList().remove(queueConfig.getProcessingQueueKey(), 1, taskId);
            
            logger.info("评价任务完成: task={}, course={}, student={}", 
                    taskId, task.getCourseId(), task.getStudentId());
            return true;
        } catch (JsonProcessingException e) {
            logger.error("完成评价任务失败: task={}", taskId, e);
            return false;
        }
    }

    public boolean failTask(String taskId, String errorMessage) {
        try {
            String taskKey = getTaskKey(taskId);
            String taskJson = (String) redisTemplate.opsForValue().get(taskKey);
            if (taskJson == null) {
                return false;
            }

            ReviewTask task = objectMapper.readValue(taskJson, ReviewTask.class);
            redisTemplate.opsForList().remove(queueConfig.getProcessingQueueKey(), 1, taskId);

            if (task.canRetry(queueConfig.getMaxRetryAttempts())) {
                task.incrementRetry();
                redisTemplate.opsForValue().set(taskKey, objectMapper.writeValueAsString(task), 24, TimeUnit.HOURS);
                redisTemplate.opsForList().rightPush(queueConfig.getRetryQueueKey(), taskId);
                logger.warn("评价任务重试: task={}, retry={}, error={}", 
                        taskId, task.getRetryCount(), errorMessage);
            } else {
                task.markFailed(errorMessage);
                redisTemplate.opsForValue().set(taskKey, objectMapper.writeValueAsString(task), 24, TimeUnit.HOURS);
                redisTemplate.opsForList().rightPush(queueConfig.getDeadLetterQueueKey(), taskId);
                logger.error("评价任务进入死信队列: task={}, error={}", taskId, errorMessage);
            }
            
            return true;
        } catch (JsonProcessingException e) {
            logger.error("处理失败任务失败: task={}", taskId, e);
            return false;
        }
    }

    public ReviewTask getTask(String taskId) {
        try {
            String taskKey = getTaskKey(taskId);
            String taskJson = (String) redisTemplate.opsForValue().get(taskKey);
            if (taskJson == null) {
                return null;
            }
            return objectMapper.readValue(taskJson, ReviewTask.class);
        } catch (JsonProcessingException e) {
            logger.error("获取任务失败: task={}", taskId, e);
            return null;
        }
    }

    public List<String> getPendingTaskIds() {
        Long size = redisTemplate.opsForList().size(queueConfig.getQueueName());
        if (size == null || size == 0) {
            return Collections.emptyList();
        }
        List<Object> tasks = redisTemplate.opsForList().range(queueConfig.getQueueName(), 0, -1);
        List<String> result = new ArrayList<>();
        for (Object task : tasks) {
            result.add((String) task);
        }
        return result;
    }

    public long getPendingTaskCount() {
        Long count = redisTemplate.opsForList().size(queueConfig.getQueueName());
        return count != null ? count : 0;
    }

    public long getProcessingTaskCount() {
        Long count = redisTemplate.opsForList().size(queueConfig.getProcessingQueueKey());
        return count != null ? count : 0;
    }

    public long getRetryTaskCount() {
        Long count = redisTemplate.opsForList().size(queueConfig.getRetryQueueKey());
        return count != null ? count : 0;
    }

    public long getDeadLetterTaskCount() {
        Long count = redisTemplate.opsForList().size(queueConfig.getDeadLetterQueueKey());
        return count != null ? count : 0;
    }

    public int getTaskRetryCount(String taskId) {
        ReviewTask task = getTask(taskId);
        return task != null ? task.getRetryCount() : 0;
    }

    public void moveRetryTasksToMainQueue() {
        String retryQueue = queueConfig.getRetryQueueKey();
        Long size = redisTemplate.opsForList().size(retryQueue);
        if (size == null || size == 0) {
            return;
        }

        for (int i = 0; i < size; i++) {
            String taskId = (String) redisTemplate.opsForList().leftPop(retryQueue);
            if (taskId != null) {
                redisTemplate.opsForList().rightPush(queueConfig.getQueueName(), taskId);
                logger.debug("重试任务移回主队列: task={}", taskId);
            }
        }
        
        if (size > 0) {
            logger.info("移动重试任务到主队列: count={}", size);
        }
    }

    private String getTaskKey(String taskId) {
        return queueConfig.getTaskKeyPrefix() + taskId;
    }

    private String generateTaskId() {
        return "review_task_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
