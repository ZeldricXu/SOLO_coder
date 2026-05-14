package com.adplatform.queue;

import com.adplatform.config.AdPlatformConfig;
import com.adplatform.dto.EffectEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class EffectEventQueue {
    private static final Logger logger = LoggerFactory.getLogger(EffectEventQueue.class);
    
    private final RedissonClient redissonClient;
    private final AdPlatformConfig config;
    private final ObjectMapper objectMapper;
    
    private final RBlockingQueue<String> eventQueue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService workerExecutor;

    public EffectEventQueue(RedissonClient redissonClient,
                           AdPlatformConfig config,
                           ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.config = config;
        this.objectMapper = objectMapper;
        
        String queueKey = config.getEffect().getQueueKey();
        this.eventQueue = redissonClient.getBlockingQueue(queueKey);
    }

    @PostConstruct
    public void init() {
        int workerThreads = config.getEffect().getWorkerThreads();
        this.workerExecutor = Executors.newFixedThreadPool(workerThreads);
        logger.info("效果事件队列初始化完成，Worker线程数: {}", workerThreads);
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
        logger.info("效果事件队列已关闭");
    }

    public boolean offer(EffectEvent event) {
        try {
            String eventJson = objectMapper.writeValueAsString(event);
            boolean success = eventQueue.offer(eventJson);
            if (success) {
                logger.debug("效果事件已提交到Redis队列: adId={}, eventType={}", 
                        event.getAdId(), event.getEventType());
            } else {
                logger.error("效果事件提交失败，队列已满: adId={}", event.getAdId());
            }
            return success;
        } catch (Exception e) {
            logger.error("序列化效果事件失败: adId={}", event.getAdId(), e);
            return false;
        }
    }

    public EffectEvent take() throws InterruptedException {
        String eventJson = eventQueue.take();
        return parseEvent(eventJson);
    }

    public EffectEvent pollEvent(long timeout, TimeUnit unit) throws InterruptedException {
        String eventJson = eventQueue.poll(timeout, unit);
        if (eventJson == null) {
            return null;
        }
        return parseEvent(eventJson);
    }

    public void processEvents(Consumer<EffectEvent> consumer) {
        int workerThreads = config.getEffect().getWorkerThreads();
        int batchSize = config.getEffect().getBatchSize();
        
        for (int i = 0; i < workerThreads; i++) {
            final int workerId = i;
            workerExecutor.submit(() -> {
                logger.info("效果事件Worker-{} 启动", workerId);
                while (running.get()) {
                    try {
                        List<EffectEvent> batch = new ArrayList<>(batchSize);
                        int count = 0;
                        
                        while (count < batchSize && running.get()) {
                            EffectEvent event = pollEvent(100, TimeUnit.MILLISECONDS);
                            if (event != null) {
                                batch.add(event);
                                count++;
                            } else {
                                break;
                            }
                        }
                        
                        if (!batch.isEmpty()) {
                            for (EffectEvent event : batch) {
                                try {
                                    consumer.accept(event);
                                } catch (Exception e) {
                                    logger.error("Worker-{} 处理单个效果事件失败: adId={}", 
                                            workerId, event.getAdId(), e);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warn("效果事件Worker-{} 被中断", workerId);
                        break;
                    } catch (Exception e) {
                        logger.error("效果事件Worker-{} 发生异常", workerId, e);
                    }
                }
                logger.info("效果事件Worker-{} 停止", workerId);
            });
        }
    }

    public int getPendingEventCount() {
        return eventQueue.size();
    }

    public void clearAllEvents() {
        eventQueue.clear();
        logger.warn("已清空所有效果事件");
    }

    private EffectEvent parseEvent(String eventJson) {
        try {
            return objectMapper.readValue(eventJson, EffectEvent.class);
        } catch (Exception e) {
            logger.error("反序列化效果事件失败", e);
            throw new RuntimeException("反序列化效果事件失败", e);
        }
    }
}
