package com.authcenter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class MfaTaskQueueService {
    
    private static final Logger logger = LoggerFactory.getLogger(MfaTaskQueueService.class);
    
    private static final String QUEUE_KEY = "mfa:task:queue";
    private static final String PROCESSING_KEY = "mfa:task:processing";
    private static final String TASK_PREFIX = "mfa:task:";
    private static final String RETRY_COUNT_KEY = "mfa:task:retry:";
    private static final String DEAD_LETTER_KEY = "mfa:task:dead_letter";
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Value("${mfa.task.max-retry:3}")
    private int maxRetry;
    
    @Value("${mfa.task.retry-delay:30000}")
    private long retryDelay;
    
    @Value("${mfa.task.timeout:120000}")
    private long taskTimeout;
    
    public static class MfaTask {
        private String taskId;
        private String userId;
        private String mfaType;
        private String mfaCode;
        private String target;
        private String userAgent;
        private String ipAddress;
        private int retryCount;
        private long createdAt;
        private Map<String, Object> extra;
        
        public MfaTask() {
        }
        
        public String getTaskId() {
            return taskId;
        }
        
        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }
        
        public String getUserId() {
            return userId;
        }
        
        public void setUserId(String userId) {
            this.userId = userId;
        }
        
        public String getMfaType() {
            return mfaType;
        }
        
        public void setMfaType(String mfaType) {
            this.mfaType = mfaType;
        }
        
        public String getMfaCode() {
            return mfaCode;
        }
        
        public void setMfaCode(String mfaCode) {
            this.mfaCode = mfaCode;
        }
        
        public String getTarget() {
            return target;
        }
        
        public void setTarget(String target) {
            this.target = target;
        }
        
        public String getUserAgent() {
            return userAgent;
        }
        
        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }
        
        public String getIpAddress() {
            return ipAddress;
        }
        
        public void setIpAddress(String ipAddress) {
            this.ipAddress = ipAddress;
        }
        
        public int getRetryCount() {
            return retryCount;
        }
        
        public void setRetryCount(int retryCount) {
            this.retryCount = retryCount;
        }
        
        public long getCreatedAt() {
            return createdAt;
        }
        
        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }
        
        public Map<String, Object> getExtra() {
            return extra;
        }
        
        public void setExtra(Map<String, Object> extra) {
            this.extra = extra;
        }
    }
    
    public MfaTask submitTask(String userId, String mfaType, String mfaCode, String target) {
        return submitTask(userId, mfaType, mfaCode, target, null, null);
    }
    
    public MfaTask submitTask(String userId, String mfaType, String mfaCode, String target, String userAgent, String ipAddress) {
        MfaTask task = new MfaTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setUserId(userId);
        task.setMfaType(mfaType);
        task.setMfaCode(mfaCode);
        task.setTarget(target);
        task.setUserAgent(userAgent);
        task.setIpAddress(ipAddress);
        task.setRetryCount(0);
        task.setCreatedAt(System.currentTimeMillis());
        
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            
            redisTemplate.opsForValue().set(TASK_PREFIX + task.getTaskId(), taskJson, 60, TimeUnit.MINUTES);
            redisTemplate.opsForList().leftPush(QUEUE_KEY, task.getTaskId());
            
            logger.info("MFA task submitted: taskId={}, type={}, userId={}", task.getTaskId(), mfaType, userId);
            return task;
            
        } catch (Exception e) {
            logger.error("Failed to submit MFA task: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to submit MFA task", e);
        }
    }
    
    public MfaTask claimTask() {
        String taskId = redisTemplate.opsForList().rightPop(QUEUE_KEY);
        if (taskId == null) {
            return null;
        }
        
        try {
            String taskJson = redisTemplate.opsForValue().get(TASK_PREFIX + taskId);
            if (taskJson == null) {
                logger.warn("Task not found in store: {}", taskId);
                return null;
            }
            
            MfaTask task = objectMapper.readValue(taskJson, MfaTask.class);
            
            String processingInfo = String.format("%s:%d", taskId, System.currentTimeMillis());
            redisTemplate.opsForHash().put(PROCESSING_KEY, taskId, processingInfo);
            redisTemplate.expire(TASK_PREFIX + taskId, 5, TimeUnit.MINUTES);
            
            logger.debug("Task claimed: taskId={}", taskId);
            return task;
            
        } catch (Exception e) {
            logger.error("Failed to claim task {}: {}", taskId, e.getMessage(), e);
            return null;
        }
    }
    
    public void completeTask(MfaTask task) {
        redisTemplate.opsForHash().delete(PROCESSING_KEY, task.getTaskId());
        redisTemplate.delete(TASK_PREFIX + task.getTaskId());
        logger.info("MFA task completed: taskId={}", task.getTaskId());
    }
    
    public void failTask(MfaTask task, String reason) {
        int retryCount = task.getRetryCount() + 1;
        task.setRetryCount(retryCount);
        
        if (retryCount >= maxRetry) {
            moveToDeadLetter(task, reason);
        } else {
            retryTask(task, reason);
        }
    }
    
    private void retryTask(MfaTask task, String reason) {
        try {
            logger.info("Retrying MFA task: taskId={}, retry={}, reason={}", 
                    task.getTaskId(), task.getRetryCount(), reason);
            
            redisTemplate.opsForHash().delete(PROCESSING_KEY, task.getTaskId());
            redisTemplate.opsForValue().set(RETRY_COUNT_KEY + task.getTaskId(), 
                    String.valueOf(task.getRetryCount()), 1, TimeUnit.HOURS);
            
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForValue().set(TASK_PREFIX + task.getTaskId(), taskJson, 60, TimeUnit.MINUTES);
            
            redisTemplate.opsForList().leftPush(QUEUE_KEY, task.getTaskId());
            
        } catch (Exception e) {
            logger.error("Failed to retry task {}: {}", task.getTaskId(), e.getMessage(), e);
            moveToDeadLetter(task, "retry_failed:" + e.getMessage());
        }
    }
    
    private void moveToDeadLetter(MfaTask task, String reason) {
        try {
            Map<String, Object> deadLetterInfo = new HashMap<>();
            deadLetterInfo.put("task", task);
            deadLetterInfo.put("reason", reason);
            deadLetterInfo.put("failedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            
            String deadLetterJson = objectMapper.writeValueAsString(deadLetterInfo);
            redisTemplate.opsForList().leftPush(DEAD_LETTER_KEY, deadLetterJson);
            
            redisTemplate.opsForHash().delete(PROCESSING_KEY, task.getTaskId());
            redisTemplate.delete(TASK_PREFIX + task.getTaskId());
            redisTemplate.delete(RETRY_COUNT_KEY + task.getTaskId());
            
            logger.error("MFA task moved to dead letter queue: taskId={}, reason={}", 
                    task.getTaskId(), reason);
                    
        } catch (Exception e) {
            logger.error("Failed to move task {} to dead letter: {}", task.getTaskId(), e.getMessage(), e);
        }
    }
    
    public long getPendingTaskCount() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }
    
    public long getProcessingTaskCount() {
        Long size = redisTemplate.opsForHash().size(PROCESSING_KEY);
        return size != null ? size : 0;
    }
    
    public long getDeadLetterCount() {
        Long size = redisTemplate.opsForList().size(DEAD_LETTER_KEY);
        return size != null ? size : 0;
    }
    
    public List<MfaTask> getDeadLetterTasks(int limit) {
        List<String> deadLetters = redisTemplate.opsForList().range(DEAD_LETTER_KEY, 0, limit - 1);
        List<MfaTask> tasks = new ArrayList<>();
        
        if (deadLetters != null) {
            for (String json : deadLetters) {
                try {
                    Map<String, Object> info = objectMapper.readValue(json, Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> taskMap = (Map<String, Object>) info.get("task");
                    String taskJson = objectMapper.writeValueAsString(taskMap);
                    MfaTask task = objectMapper.readValue(taskJson, MfaTask.class);
                    tasks.add(task);
                } catch (Exception e) {
                    logger.warn("Failed to parse dead letter task: {}", e.getMessage());
                }
            }
        }
        
        return tasks;
    }
    
    public void clearDeadLetterQueue() {
        redisTemplate.delete(DEAD_LETTER_KEY);
        logger.info("Dead letter queue cleared");
    }
    
    public void reprocessDeadLetterTask(int index) {
        String deadLetterJson = redisTemplate.opsForList().index(DEAD_LETTER_KEY, index);
        if (deadLetterJson == null) {
            return;
        }
        
        try {
            Map<String, Object> info = objectMapper.readValue(deadLetterJson, Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> taskMap = (Map<String, Object>) info.get("task");
            String taskJson = objectMapper.writeValueAsString(taskMap);
            MfaTask task = objectMapper.readValue(taskJson, MfaTask.class);
            task.setRetryCount(0);
            
            String newTaskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForValue().set(TASK_PREFIX + task.getTaskId(), newTaskJson, 60, TimeUnit.MINUTES);
            redisTemplate.opsForList().leftPush(QUEUE_KEY, task.getTaskId());
            
            redisTemplate.opsForList().remove(DEAD_LETTER_KEY, 1, deadLetterJson);
            
            logger.info("Dead letter task reprocessed: taskId={}", task.getTaskId());
            
        } catch (Exception e) {
            logger.error("Failed to reprocess dead letter task: {}", e.getMessage(), e);
        }
    }
    
    public void cleanupStaleProcessingTasks() {
        Map<Object, Object> processing = redisTemplate.opsForHash().entries(PROCESSING_KEY);
        long now = System.currentTimeMillis();
        
        for (Map.Entry<Object, Object> entry : processing.entrySet()) {
            String taskId = (String) entry.getKey();
            String[] parts = ((String) entry.getValue()).split(":");
            if (parts.length >= 2) {
                long startTime = Long.parseLong(parts[1]);
                if (now - startTime > taskTimeout) {
                    logger.warn("Found stale processing task: {}, started {}ms ago", taskId, now - startTime);
                    
                    String taskJson = redisTemplate.opsForValue().get(TASK_PREFIX + taskId);
                    if (taskJson != null) {
                        try {
                            MfaTask task = objectMapper.readValue(taskJson, MfaTask.class);
                            failTask(task, "stale_timeout");
                        } catch (Exception e) {
                            logger.error("Failed to handle stale task {}: {}", taskId, e.getMessage());
                            redisTemplate.opsForHash().delete(PROCESSING_KEY, taskId);
                        }
                    } else {
                        redisTemplate.opsForHash().delete(PROCESSING_KEY, taskId);
                    }
                }
            }
        }
    }
}