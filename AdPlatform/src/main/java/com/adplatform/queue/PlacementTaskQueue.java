package com.adplatform.queue;

import com.adplatform.config.AdPlatformConfig;
import com.adplatform.dto.PlacementRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class PlacementTaskQueue {
    private static final Logger logger = LoggerFactory.getLogger(PlacementTaskQueue.class);
    
    private final RedissonClient redissonClient;
    private final AdPlatformConfig config;
    private final ObjectMapper objectMapper;
    
    private final RBlockingQueue<String> taskQueue;
    private final RDelayedQueue<String> delayedQueue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService workerExecutor;

    public PlacementTaskQueue(RedissonClient redissonClient, 
                              AdPlatformConfig config,
                              ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.config = config;
        this.objectMapper = objectMapper;
        
        String queueKey = config.getPlacement().getQueueKey();
        this.taskQueue = redissonClient.getBlockingQueue(queueKey);
        this.delayedQueue = redissonClient.getDelayedQueue(taskQueue);
    }

    @PostConstruct
    public void init() {
        int workerThreads = config.getPlacement().getWorkerThreads();
        this.workerExecutor = Executors.newFixedThreadPool(workerThreads);
        logger.info("投放任务队列初始化完成，Worker线程数: {}", workerThreads);
    }

    @PreDestroy
    public void destroy() {
        running.set(false);
        if (workerExecutor != null) {
            workerExecutor.shutdown();
            try {
                if (!workerExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    workerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("投放任务队列已关闭");
    }

    public boolean submitTask(PlacementRequest task) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            boolean success = taskQueue.offer(taskJson);
            if (success) {
                logger.info("投放任务已提交到Redis队列: adId={}", task.getAdId());
            } else {
                logger.error("投放任务提交失败，队列已满: adId={}", task.getAdId());
            }
            return success;
        } catch (Exception e) {
            logger.error("序列化投放任务失败: adId={}", task.getAdId(), e);
            return false;
        }
    }

    public boolean submitDelayedTask(PlacementRequest task, long delay, TimeUnit unit) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            delayedQueue.offer(taskJson, delay, unit);
            logger.info("延迟投放任务已提交到Redis队列: adId={}, delay={}ms", 
                    task.getAdId(), unit.toMillis(delay));
            return true;
        } catch (Exception e) {
            logger.error("序列化延迟投放任务失败: adId={}", task.getAdId(), e);
            return false;
        }
    }

    public PlacementRequest takeTask() throws InterruptedException {
        String taskJson = taskQueue.take();
        return parseTask(taskJson);
    }

    public PlacementRequest pollTask(long timeout, TimeUnit unit) throws InterruptedException {
        String taskJson = taskQueue.poll(timeout, unit);
        if (taskJson == null) {
            return null;
        }
        return parseTask(taskJson);
    }

    public void startWorkers(Consumer<PlacementRequest> taskConsumer) {
        int workerThreads = config.getPlacement().getWorkerThreads();
        int maxRetries = config.getPlacement().getMaxRetries();
        long retryInterval = config.getPlacement().getRetryInterval();
        
        for (int i = 0; i < workerThreads; i++) {
            final int workerId = i;
            workerExecutor.submit(() -> {
                logger.info("投放任务Worker-{} 启动", workerId);
                while (running.get()) {
                    try {
                        PlacementRequest task = takeTask();
                        if (task == null) {
                            continue;
                        }
                        
                        int retryCount = 0;
                        boolean success = false;
                        
                        while (retryCount < maxRetries && !success && running.get()) {
                            try {
                                logger.info("Worker-{} 处理投放任务: adId={}, 重试次数={}", 
                                        workerId, task.getAdId(), retryCount);
                                taskConsumer.accept(task);
                                success = true;
                                logger.info("Worker-{} 投放任务处理成功: adId={}", 
                                        workerId, task.getAdId());
                            } catch (Exception e) {
                                retryCount++;
                                logger.error("Worker-{} 投放任务处理失败: adId={}, 重试次数={}", 
                                        workerId, task.getAdId(), retryCount, e);
                                
                                if (retryCount < maxRetries) {
                                    logger.info("Worker-{} 等待重试投放任务: adId={}, 等待时间={}ms", 
                                            workerId, task.getAdId(), retryInterval);
                                    Thread.sleep(retryInterval);
                                } else {
                                    logger.error("Worker-{} 投放任务处理超过最大重试次数，任务将被丢弃: adId={}", 
                                            workerId, task.getAdId());
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warn("投放任务Worker-{} 被中断", workerId);
                        break;
                    } catch (Exception e) {
                        logger.error("投放任务Worker-{} 发生异常", workerId, e);
                    }
                }
                logger.info("投放任务Worker-{} 停止", workerId);
            });
        }
    }

    public int getPendingTaskCount() {
        return taskQueue.size();
    }

    public void clearAllTasks() {
        taskQueue.clear();
        delayedQueue.clear();
        logger.warn("已清空所有投放任务");
    }

    private PlacementRequest parseTask(String taskJson) {
        try {
            return objectMapper.readValue(taskJson, PlacementRequest.class);
        } catch (Exception e) {
            logger.error("反序列化投放任务失败", e);
            throw new RuntimeException("反序列化投放任务失败", e);
        }
    }
}
