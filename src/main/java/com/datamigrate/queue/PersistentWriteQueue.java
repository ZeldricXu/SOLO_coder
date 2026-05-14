package com.datamigrate.queue;

import com.datamigrate.config.RedisConfig;
import com.datamigrate.service.AsyncWriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class PersistentWriteQueue {

    private final RedisQueueManager redisQueueManager;
    private final RedisConfig redisConfig;

    private final Map<String, BlockingQueue<AsyncWriteService.WriteTask>> memoryQueues = 
        new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (redisQueueManager.isRedisAvailable()) {
            recoverFromRedis();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (redisQueueManager.isRedisAvailable()) {
            persistMemoryQueuesToRedis();
        }
    }

    private void recoverFromRedis() {
        Set<String> taskQueues = redisQueueManager.getActiveTaskQueues();
        log.info("从Redis恢复队列，发现{}个活动队列", taskQueues.size());
        
        for (String queueKey : taskQueues) {
            long size = redisQueueManager.getQueueSize(extractTaskId(queueKey));
            if (size > 0) {
                log.info("队列{}有待处理任务{}个", queueKey, size);
            }
        }
    }

    private String extractTaskId(String queueKey) {
        String prefix = redisConfig.getQueueKeyPrefix();
        if (queueKey.startsWith(prefix)) {
            return queueKey.substring(prefix.length());
        }
        return queueKey;
    }

    private void persistMemoryQueuesToRedis() {
        for (Map.Entry<String, BlockingQueue<AsyncWriteService.WriteTask>> entry : memoryQueues.entrySet()) {
            String taskId = entry.getKey();
            BlockingQueue<AsyncWriteService.WriteTask> queue = entry.getValue();
            
            while (!queue.isEmpty()) {
                AsyncWriteService.WriteTask task = queue.poll();
                if (task != null) {
                    redisQueueManager.offer(task);
                }
            }
        }
    }

    public boolean offer(AsyncWriteService.WriteTask task) {
        if (redisQueueManager.isRedisAvailable()) {
            boolean success = redisQueueManager.offer(task);
            if (success) {
                return true;
            }
            log.warn("Redis写入失败，降级到内存队列: taskId={}", task.getTaskId());
        }
        return getMemoryQueue(task.getTaskId()).offer(task);
    }

    public AsyncWriteService.WriteTask take(String taskId, long timeout, TimeUnit unit) 
            throws InterruptedException {
        if (redisQueueManager.isRedisAvailable()) {
            AsyncWriteService.WriteTask task = redisQueueManager.take(taskId, timeout, unit);
            if (task != null) {
                return task;
            }
        }
        return getMemoryQueue(taskId).poll(timeout, unit);
    }

    public List<AsyncWriteService.WriteTask> drainTo(String taskId, int maxElements) {
        List<AsyncWriteService.WriteTask> tasks = new ArrayList<>();
        
        if (redisQueueManager.isRedisAvailable()) {
            List<AsyncWriteService.WriteTask> redisTasks = redisQueueManager.drainFromRedis(taskId, maxElements);
            tasks.addAll(redisTasks);
        }
        
        if (tasks.size() < maxElements) {
            int remaining = maxElements - tasks.size();
            BlockingQueue<AsyncWriteService.WriteTask> memQueue = getMemoryQueue(taskId);
            for (int i = 0; i < remaining && !memQueue.isEmpty(); i++) {
                AsyncWriteService.WriteTask task = memQueue.poll();
                if (task != null) {
                    tasks.add(task);
                }
            }
        }
        
        return tasks;
    }

    public long size(String taskId) {
        long redisSize = redisQueueManager.isRedisAvailable() ? 
            redisQueueManager.getQueueSize(taskId) : 0;
        long memorySize = getMemoryQueue(taskId).size();
        return redisSize + memorySize;
    }

    public boolean isEmpty(String taskId) {
        return size(taskId) == 0;
    }

    public void clear(String taskId) {
        if (redisQueueManager.isRedisAvailable()) {
            redisQueueManager.clearQueue(taskId);
        }
        getMemoryQueue(taskId).clear();
        log.info("已清空任务队列: taskId={}", taskId);
    }

    private BlockingQueue<AsyncWriteService.WriteTask> getMemoryQueue(String taskId) {
        return memoryQueues.computeIfAbsent(taskId, k -> new LinkedBlockingQueue<>());
    }
}
