package com.servicedesk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.config.ServiceDeskProperties;
import com.servicedesk.entity.PriorityTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisPriorityQueueService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ServiceDeskProperties properties;
    private final PriorityService priorityService;

    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Consumer<String>> completionHandlers = new ArrayList<>();
    private final List<Consumer<Exception>> errorHandlers = new ArrayList<>();

    @PostConstruct
    public void init() {
        if (properties.getPriorityAsync().isRedisEnabled()) {
            int poolSize = properties.getPriorityAsync().getThreadPoolSize();
            this.executorService = Executors.newFixedThreadPool(poolSize);
            startWorkers();
            log.info("Redis优先级队列服务已初始化，Worker数量: {}", poolSize);
        }
    }

    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    private void startWorkers() {
        running.set(true);
        int poolSize = properties.getPriorityAsync().getThreadPoolSize();
        for (int i = 0; i < poolSize; i++) {
            executorService.submit(this::workerLoop);
        }
        log.info("已启动 {} 个Worker处理优先级评估任务", poolSize);
    }

    private void workerLoop() {
        String queueKey = properties.getPriorityAsync().getRedisQueueKey();
        String processingKey = properties.getPriorityAsync().getRedisProcessingKey();
        String failedKey = properties.getPriorityAsync().getRedisFailedKey();
        int pollInterval = properties.getPriorityAsync().getPollIntervalMs();
        long expirationSeconds = properties.getPriorityAsync().getTaskExpirationSeconds();

        while (running.get()) {
            try {
                String taskJson = redisTemplate.opsForList().leftPop(queueKey, pollInterval, TimeUnit.MILLISECONDS);

                if (taskJson != null) {
                    log.debug("Worker获取到任务: {}", taskJson);

                    PriorityTask task = deserializeTask(taskJson);
                    if (task != null) {
                        try {
                            redisTemplate.opsForHash().put(processingKey, task.getTaskId(), taskJson);

                            String priority = processTask(task);

                            redisTemplate.opsForHash().delete(processingKey, task.getTaskId());

                            notifyCompletion(priority);

                            log.info("任务 {} 处理完成，优先级: {}", task.getTaskId(), priority);

                        } catch (Exception e) {
                            log.error("任务处理失败: {}", task.getTaskId(), e);

                            task.setRetryCount(task.getRetryCount() + 1);

                            if (task.getRetryCount() < properties.getPriorityAsync().getMaxRetries()) {
                                log.info("任务 {} 第{}次重试", task.getTaskId(), task.getRetryCount());
                                String updatedJson = serializeTask(task);
                                redisTemplate.opsForList().rightPush(queueKey, updatedJson);
                            } else {
                                log.error("任务 {} 重试次数已达上限，移入失败队列", task.getTaskId());
                                String failedJson = serializeTask(task);
                                redisTemplate.opsForHash().put(failedKey, task.getTaskId(), failedJson);
                                redisTemplate.expire(failedKey, Duration.ofSeconds(expirationSeconds));
                                notifyError(e);
                            }

                            redisTemplate.opsForHash().delete(processingKey, task.getTaskId());
                        }
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Worker被中断");
                break;
            } catch (Exception e) {
                log.error("Worker处理异常", e);
                try {
                    Thread.sleep(pollInterval);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private String processTask(PriorityTask task) {
        com.servicedesk.dto.CreateTicketRequest request = task.toRequest();
        return priorityService.evaluatePriority(request);
    }

    public void submitTask(PriorityTask task) {
        String queueKey = properties.getPriorityAsync().getRedisQueueKey();
        String taskJson = serializeTask(task);
        redisTemplate.opsForList().rightPush(queueKey, taskJson);
        log.info("任务已提交到Redis队列: {}", task.getTaskId());
    }

    public long getQueueSize() {
        String queueKey = properties.getPriorityAsync().getRedisQueueKey();
        Long size = redisTemplate.opsForList().size(queueKey);
        return size != null ? size : 0;
    }

    public long getProcessingCount() {
        String processingKey = properties.getPriorityAsync().getRedisProcessingKey();
        Long size = redisTemplate.opsForHash().size(processingKey);
        return size != null ? size : 0;
    }

    public long getFailedCount() {
        String failedKey = properties.getPriorityAsync().getRedisFailedKey();
        Long size = redisTemplate.opsForHash().size(failedKey);
        return size != null ? size : 0;
    }

    public Set<Object> getFailedTaskIds() {
        String failedKey = properties.getPriorityAsync().getRedisFailedKey();
        return redisTemplate.opsForHash().keys(failedKey);
    }

    public boolean retryFailedTask(String taskId) {
        String queueKey = properties.getPriorityAsync().getRedisQueueKey();
        String failedKey = properties.getPriorityAsync().getRedisFailedKey();

        Object taskJson = redisTemplate.opsForHash().get(failedKey, taskId);
        if (taskJson != null) {
            redisTemplate.opsForList().rightPush(queueKey, (String) taskJson);
            redisTemplate.opsForHash().delete(failedKey, taskId);
            log.info("失败任务已重新排队: {}", taskId);
            return true;
        }
        return false;
    }

    public void addCompletionHandler(Consumer<String> handler) {
        completionHandlers.add(handler);
    }

    public void addErrorHandler(Consumer<Exception> handler) {
        errorHandlers.add(handler);
    }

    private void notifyCompletion(String priority) {
        for (Consumer<String> handler : completionHandlers) {
            try {
                handler.accept(priority);
            } catch (Exception e) {
                log.error("通知完成处理器异常", e);
            }
        }
    }

    private void notifyError(Exception e) {
        for (Consumer<Exception> handler : errorHandlers) {
            try {
                handler.accept(e);
            } catch (Exception ex) {
                log.error("通知错误处理器异常", ex);
            }
        }
    }

    private String serializeTask(PriorityTask task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化任务失败", e);
        }
    }

    private PriorityTask deserializeTask(String json) {
        try {
            return objectMapper.readValue(json, PriorityTask.class);
        } catch (JsonProcessingException e) {
            log.error("反序列化任务失败: {}", json, e);
            return null;
        }
    }

    public void clearAllQueues() {
        String queueKey = properties.getPriorityAsync().getRedisQueueKey();
        String processingKey = properties.getPriorityAsync().getRedisProcessingKey();
        String failedKey = properties.getPriorityAsync().getRedisFailedKey();

        redisTemplate.delete(queueKey);
        redisTemplate.delete(processingKey);
        redisTemplate.delete(failedKey);
        log.info("已清空所有Redis队列");
    }
}
