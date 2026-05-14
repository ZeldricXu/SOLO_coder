package com.healthtrack.service;

import com.healthtrack.entity.HealthDataQueueTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class HealthDataQueueService {

    private static final Logger logger = LoggerFactory.getLogger(HealthDataQueueService.class);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${healthtrack.queue.health-data-key:healthtrack:health:data:queue}")
    private String queueKey;

    @Value("${healthtrack.queue.worker-poll-interval-ms:500}")
    private long pollIntervalMs;

    @Value("${healthtrack.queue.max-retry:3}")
    private int maxRetry;

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    public boolean enqueueTask(HealthDataQueueTask task) {
        try {
            task.setMaxRetry(maxRetry);
            redisTemplate.opsForList().rightPush(queueKey, task);
            logger.info("健康数据任务入队成功: taskId={}, userId={}, dataType={}", 
                    task.getTaskId(), task.getUserId(), task.getDataType());
            return true;
        } catch (Exception e) {
            logger.error("健康数据任务入队失败: taskId={}, error={}", task.getTaskId(), e.getMessage(), e);
            return false;
        }
    }

    public boolean enqueueTasks(List<HealthDataQueueTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return false;
        }
        try {
            for (HealthDataQueueTask task : tasks) {
                task.setMaxRetry(maxRetry);
            }
            redisTemplate.opsForList().rightPushAll(queueKey, tasks.toArray());
            logger.info("批量健康数据任务入队成功: count={}", tasks.size());
            return true;
        } catch (Exception e) {
            logger.error("批量健康数据任务入队失败: error={}", e.getMessage(), e);
            return false;
        }
    }

    public HealthDataQueueTask dequeueTask() {
        try {
            Object task = redisTemplate.opsForList().leftPop(queueKey, pollIntervalMs, TimeUnit.MILLISECONDS);
            if (task instanceof HealthDataQueueTask) {
                return (HealthDataQueueTask) task;
            }
            return null;
        } catch (Exception e) {
            logger.error("健康数据任务出队失败: error={}", e.getMessage(), e);
            return null;
        }
    }

    public List<HealthDataQueueTask> dequeueTasks(int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }
        try {
            List<Object> tasks = redisTemplate.opsForList().leftPop(queueKey, count);
            if (tasks == null || tasks.isEmpty()) {
                return Collections.emptyList();
            }
            List<HealthDataQueueTask> result = new ArrayList<>();
            for (Object task : tasks) {
                if (task instanceof HealthDataQueueTask) {
                    result.add((HealthDataQueueTask) task);
                }
            }
            return result;
        } catch (Exception e) {
            logger.error("批量健康数据任务出队失败: error={}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public boolean retryTask(HealthDataQueueTask task) {
        if (task == null || !task.canRetry()) {
            logger.warn("任务无法重试: taskId={}, retryCount={}, maxRetry={}", 
                    task != null ? task.getTaskId() : "null",
                    task != null ? task.getRetryCount() : 0,
                    task != null ? task.getMaxRetry() : maxRetry);
            return false;
        }
        try {
            task.incrementRetry();
            redisTemplate.opsForList().rightPush(queueKey, task);
            logger.info("健康数据任务重新入队: taskId={}, retryCount={}", 
                    task.getTaskId(), task.getRetryCount());
            return true;
        } catch (Exception e) {
            logger.error("健康数据任务重新入队失败: taskId={}, error={}", 
                    task.getTaskId(), e.getMessage(), e);
            return false;
        }
    }

    public long getQueueSize() {
        try {
            Long size = redisTemplate.opsForList().size(queueKey);
            return size != null ? size : 0;
        } catch (Exception e) {
            logger.error("获取队列大小失败: error={}", e.getMessage(), e);
            return 0;
        }
    }

    public boolean isEmpty() {
        return getQueueSize() == 0;
    }

    public void clearQueue() {
        try {
            redisTemplate.delete(queueKey);
            logger.info("健康数据队列已清空");
        } catch (Exception e) {
            logger.error("清空队列失败: error={}", e.getMessage(), e);
        }
    }

    public boolean moveToDeadLetterQueue(HealthDataQueueTask task, String reason) {
        try {
            String deadLetterKey = queueKey + ":dlq";
            redisTemplate.opsForList().rightPush(deadLetterKey, task);
            logger.warn("任务移入死信队列: taskId={}, reason={}", task.getTaskId(), reason);
            return true;
        } catch (Exception e) {
            logger.error("任务移入死信队列失败: taskId={}, error={}", task.getTaskId(), e.getMessage(), e);
            return false;
        }
    }

    public long getDeadLetterQueueSize() {
        try {
            String deadLetterKey = queueKey + ":dlq";
            Long size = redisTemplate.opsForList().size(deadLetterKey);
            return size != null ? size : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public String getQueueKey() {
        return queueKey;
    }

    public AtomicBoolean getIsProcessing() {
        return isProcessing;
    }
}
