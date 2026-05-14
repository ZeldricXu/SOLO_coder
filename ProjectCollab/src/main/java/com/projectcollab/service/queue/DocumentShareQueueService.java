package com.projectcollab.service.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projectcollab.config.properties.DocumentShareProperties;
import com.projectcollab.dto.DocumentShareTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Service
public class DocumentShareQueueService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentShareQueueService.class);

    @Autowired
    private DocumentShareProperties properties;

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final LinkedBlockingQueue<DocumentShareTask> inMemoryQueue = new LinkedBlockingQueue<>();

    public void enqueueTask(DocumentShareTask task) {
        if (properties.isUseRedisQueue() && redisTemplate != null) {
            try {
                redisTemplate.opsForList().rightPush(properties.getQueueName(), task);
                logger.debug("文档共享任务已入Redis队列: docId={}, projectId={}", task.getDocumentId(), task.getProjectId());
            } catch (Exception e) {
                logger.warn("Redis队列入队失败，回退到内存队列: {}", e.getMessage());
                enqueueInMemory(task);
            }
        } else {
            enqueueInMemory(task);
        }
    }

    private void enqueueInMemory(DocumentShareTask task) {
        inMemoryQueue.offer(task);
        logger.debug("文档共享任务已入内存队列: docId={}, projectId={}", task.getDocumentId(), task.getProjectId());
    }

    public DocumentShareTask dequeueTask() {
        if (properties.isUseRedisQueue() && redisTemplate != null) {
            try {
                Object raw = redisTemplate.opsForList().leftPop(
                        properties.getQueueName(), 
                        properties.getPollTimeoutMs(), 
                        TimeUnit.MILLISECONDS
                );
                if (raw != null) {
                    return convertToTask(raw);
                }
            } catch (Exception e) {
                logger.debug("Redis队列出队失败: {}", e.getMessage());
            }
        }
        
        try {
            return inMemoryQueue.poll(properties.getPollTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public DocumentShareTask dequeueTask(long timeout, TimeUnit unit) {
        if (properties.isUseRedisQueue() && redisTemplate != null) {
            try {
                Object raw = redisTemplate.opsForList().leftPop(
                        properties.getQueueName(), 
                        timeout, 
                        unit
                );
                if (raw != null) {
                    return convertToTask(raw);
                }
            } catch (Exception e) {
                logger.debug("Redis队列出队失败: {}", e.getMessage());
            }
        }
        
        try {
            return inMemoryQueue.poll(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private DocumentShareTask convertToTask(Object raw) {
        if (raw instanceof DocumentShareTask) {
            return (DocumentShareTask) raw;
        }
        if (raw instanceof String) {
            try {
                return objectMapper.readValue((String) raw, DocumentShareTask.class);
            } catch (Exception e) {
                logger.warn("无法解析文档共享任务JSON: {}", e.getMessage());
            }
        }
        return null;
    }

    public long getQueueSize() {
        if (properties.isUseRedisQueue() && redisTemplate != null) {
            try {
                Long size = redisTemplate.opsForList().size(properties.getQueueName());
                return size != null ? size : 0;
            } catch (Exception e) {
                logger.debug("获取Redis队列大小失败: {}", e.getMessage());
            }
        }
        return inMemoryQueue.size();
    }

    public void requeueTask(DocumentShareTask task) {
        if (task.getRetryCount() < properties.getMaxRetries()) {
            task.incrementRetryCount();
            logger.info("文档共享任务重试: taskId={}, retryCount={}", task.getTaskId(), task.getRetryCount());
            enqueueTask(task);
        } else {
            logger.error("文档共享任务超过最大重试次数，丢弃: taskId={}, docId={}", 
                    task.getTaskId(), task.getDocumentId());
        }
    }

    public boolean isUsingRedis() {
        return properties.isUseRedisQueue() && redisTemplate != null;
    }

    public void clearQueue() {
        if (properties.isUseRedisQueue() && redisTemplate != null) {
            try {
                redisTemplate.delete(properties.getQueueName());
            } catch (Exception e) {
                logger.debug("清空Redis队列失败: {}", e.getMessage());
            }
        }
        inMemoryQueue.clear();
    }
}
