package com.datamigrate.queue;

import com.datamigrate.config.RedisConfig;
import com.datamigrate.service.AsyncWriteService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisQueueManager {

    private final RedisConfig redisConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private final AtomicBoolean redisAvailable = new AtomicBoolean(false);
    private final Map<String, ExecutorService> consumerExecutors = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (redisConfig.isEnabled() && redisTemplate != null) {
            try {
                redisTemplate.opsForValue().get("ping");
                redisAvailable.set(true);
                log.info("Redis队列管理器初始化成功，队列前缀: {}", redisConfig.getQueueKeyPrefix());
            } catch (Exception e) {
                log.warn("Redis连接不可用，将使用内存队列作为备用: {}", e.getMessage());
                redisAvailable.set(false);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        consumerExecutors.values().forEach(ExecutorService::shutdown);
    }

    public boolean isRedisAvailable() {
        return redisAvailable.get() && redisConfig.isEnabled();
    }

    public String getQueueKey(String taskId) {
        return redisConfig.getQueueKeyPrefix() + taskId;
    }

    public boolean offer(AsyncWriteService.WriteTask task) {
        if (isRedisAvailable()) {
            return offerToRedis(task);
        }
        log.warn("Redis不可用，任务{}将使用内存队列", task.getTaskId());
        return true;
    }

    private boolean offerToRedis(AsyncWriteService.WriteTask task) {
        try {
            String queueKey = getQueueKey(task.getTaskId());
            String json = serializeTask(task);
            Long result = redisTemplate.opsForList().rightPush(queueKey, json);
            return result != null && result > 0;
        } catch (Exception e) {
            log.error("任务写入Redis队列失败: taskId={}", task.getTaskId(), e);
            return false;
        }
    }

    public AsyncWriteService.WriteTask take(String taskId, long timeout, TimeUnit unit) 
            throws InterruptedException {
        if (isRedisAvailable()) {
            return takeFromRedis(taskId, timeout, unit);
        }
        return null;
    }

    private AsyncWriteService.WriteTask takeFromRedis(String taskId, long timeout, TimeUnit unit) 
            throws InterruptedException {
        try {
            String queueKey = getQueueKey(taskId);
            ListOperations<String, String> ops = redisTemplate.opsForList();
            String json = ops.leftPop(queueKey, timeout, unit);
            
            if (json != null) {
                return deserializeTask(json);
            }
        } catch (Exception e) {
            log.error("从Redis队列读取任务失败: taskId={}", taskId, e);
        }
        return null;
    }

    public List<AsyncWriteService.WriteTask> drainFromRedis(String taskId, int maxCount) {
        List<AsyncWriteService.WriteTask> tasks = new ArrayList<>();
        if (!isRedisAvailable()) {
            return tasks;
        }

        try {
            String queueKey = getQueueKey(taskId);
            ListOperations<String, String> ops = redisTemplate.opsForList();
            
            for (int i = 0; i < maxCount; i++) {
                String json = ops.leftPop(queueKey);
                if (json == null) {
                    break;
                }
                AsyncWriteService.WriteTask task = deserializeTask(json);
                if (task != null) {
                    tasks.add(task);
                }
            }
        } catch (Exception e) {
            log.error("从Redis批量读取任务失败: taskId={}", taskId, e);
        }
        
        return tasks;
    }

    public long getQueueSize(String taskId) {
        if (!isRedisAvailable()) {
            return 0;
        }
        try {
            String queueKey = getQueueKey(taskId);
            Long size = redisTemplate.opsForList().size(queueKey);
            return size != null ? size : 0;
        } catch (Exception e) {
            log.error("获取Redis队列大小失败: taskId={}", taskId, e);
            return 0;
        }
    }

    public boolean isQueueEmpty(String taskId) {
        return getQueueSize(taskId) == 0;
    }

    public void clearQueue(String taskId) {
        if (!isRedisAvailable()) {
            return;
        }
        try {
            String queueKey = getQueueKey(taskId);
            redisTemplate.delete(queueKey);
            log.info("已清空Redis队列: {}", queueKey);
        } catch (Exception e) {
            log.error("清空Redis队列失败: taskId={}", taskId, e);
        }
    }

    public Set<String> getActiveTaskQueues() {
        if (!isRedisAvailable()) {
            return Collections.emptySet();
        }
        try {
            String pattern = redisConfig.getQueueKeyPrefix() + "*";
            Set<String> keys = redisTemplate.keys(pattern);
            return keys != null ? keys : Collections.emptySet();
        } catch (Exception e) {
            log.error("获取活动队列失败", e);
            return Collections.emptySet();
        }
    }

    private String serializeTask(AsyncWriteService.WriteTask task) throws JsonProcessingException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getTaskId());
        data.put("record", task.getRecord());
        data.put("retryCount", task.getRetryCount());
        data.put("maxRetries", task.getMaxRetries());
        data.put("createdAt", task.getCreatedAt());
        return objectMapper.writeValueAsString(data);
    }

    private AsyncWriteService.WriteTask deserializeTask(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(json, Map.class);
            
            String taskId = (String) data.get("taskId");
            @SuppressWarnings("unchecked")
            Map<String, Object> record = (Map<String, Object>) data.get("record");
            int retryCount = data.containsKey("retryCount") ? 
                ((Number) data.get("retryCount")).intValue() : 0;
            int maxRetries = data.containsKey("maxRetries") ? 
                ((Number) data.get("maxRetries")).intValue() : 3;

            return new AsyncWriteService.WriteTask(taskId, record, retryCount, maxRetries);
        } catch (Exception e) {
            log.error("反序列化任务失败: {}", json, e);
            return null;
        }
    }
}
