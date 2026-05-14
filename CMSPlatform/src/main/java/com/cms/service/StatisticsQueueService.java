package com.cms.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class StatisticsQueueService {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsQueueService.class);

    private static final String VIEW_QUEUE_KEY = "cms:statistics:view:queue";
    private static final String LIKE_QUEUE_KEY = "cms:statistics:like:queue";
    private static final String SHARE_QUEUE_KEY = "cms:statistics:share:queue";
    
    private static final String TASK_SET_KEY = "cms:statistics:tasks:pending";
    private static final String TASK_HASH_PREFIX = "cms:statistics:task:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    public StatisticsQueueService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public String enqueueViewTask(String contentId, String userId, String sessionId) {
        return enqueueTask(VIEW_QUEUE_KEY, "view", contentId, userId, sessionId);
    }

    public String enqueueLikeTask(String contentId, String userId, String sessionId) {
        return enqueueTask(LIKE_QUEUE_KEY, "like", contentId, userId, sessionId);
    }

    public String enqueueShareTask(String contentId, String userId, String sessionId) {
        return enqueueTask(SHARE_QUEUE_KEY, "share", contentId, userId, sessionId);
    }

    private String enqueueTask(String queueKey, String operationType, String contentId, 
                               String userId, String sessionId) {
        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        
        Map<String, Object> taskData = new HashMap<>();
        taskData.put("taskId", taskId);
        taskData.put("operationType", operationType);
        taskData.put("contentId", contentId);
        taskData.put("userId", userId);
        taskData.put("sessionId", sessionId);
        taskData.put("createdAt", LocalDateTime.now().toString());
        taskData.put("status", "pending");

        try {
            String taskJson = objectMapper.writeValueAsString(taskData);
            
            redisTemplate.opsForList().rightPush(queueKey, taskId);
            redisTemplate.opsForHash().put(TASK_HASH_PREFIX + taskId, "data", taskJson);
            redisTemplate.opsForSet().add(TASK_SET_KEY, taskId);
            
            logger.debug("入队统计任务: taskId={}, operation={}, contentId={}", 
                taskId, operationType, contentId);
            
            return taskId;
        } catch (JsonProcessingException e) {
            logger.error("序列化统计任务失败", e);
            throw new RuntimeException("序列化统计任务失败", e);
        }
    }

    public String dequeueViewTask() {
        return dequeueTask(VIEW_QUEUE_KEY);
    }

    public String dequeueLikeTask() {
        return dequeueTask(LIKE_QUEUE_KEY);
    }

    public String dequeueShareTask() {
        return dequeueTask(SHARE_QUEUE_KEY);
    }

    private String dequeueTask(String queueKey) {
        String taskId = redisTemplate.opsForList().leftPop(queueKey);
        if (taskId == null) {
            return null;
        }

        String taskJson = (String) redisTemplate.opsForHash().get(TASK_HASH_PREFIX + taskId, "data");
        return taskJson;
    }

    public void markTaskProcessing(String taskId) {
        updateTaskStatus(taskId, "processing");
    }

    public void markTaskCompleted(String taskId) {
        updateTaskStatus(taskId, "completed");
        redisTemplate.opsForSet().remove(TASK_SET_KEY, taskId);
        redisTemplate.delete(TASK_HASH_PREFIX + taskId);
    }

    public void markTaskFailed(String taskId, String errorMessage) {
        updateTaskStatus(taskId, "failed");
        redisTemplate.opsForHash().put(TASK_HASH_PREFIX + taskId, "error", errorMessage);
    }

    private void updateTaskStatus(String taskId, String status) {
        String taskJson = (String) redisTemplate.opsForHash().get(TASK_HASH_PREFIX + taskId, "data");
        if (taskJson != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> taskData = objectMapper.readValue(taskJson, Map.class);
                taskData.put("status", status);
                taskData.put("updatedAt", LocalDateTime.now().toString());
                redisTemplate.opsForHash().put(TASK_HASH_PREFIX + taskId, "data", 
                    objectMapper.writeValueAsString(taskData));
            } catch (JsonProcessingException e) {
                logger.error("更新任务状态失败: taskId={}", taskId, e);
            }
        }
    }

    public long getPendingViewTaskCount() {
        Long count = redisTemplate.opsForList().size(VIEW_QUEUE_KEY);
        return count != null ? count : 0;
    }

    public long getPendingLikeTaskCount() {
        Long count = redisTemplate.opsForList().size(LIKE_QUEUE_KEY);
        return count != null ? count : 0;
    }

    public long getPendingShareTaskCount() {
        Long count = redisTemplate.opsForList().size(SHARE_QUEUE_KEY);
        return count != null ? count : 0;
    }

    public long getTotalPendingTasks() {
        Long count = redisTemplate.opsForSet().size(TASK_SET_KEY);
        return count != null ? count : 0;
    }

    public void clearAllPendingTasks() {
        redisTemplate.delete(VIEW_QUEUE_KEY);
        redisTemplate.delete(LIKE_QUEUE_KEY);
        redisTemplate.delete(SHARE_QUEUE_KEY);
        redisTemplate.delete(TASK_SET_KEY);
    }
}
