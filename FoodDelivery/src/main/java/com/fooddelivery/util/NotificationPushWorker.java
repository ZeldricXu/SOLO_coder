package com.fooddelivery.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class NotificationPushWorker {

    @Autowired
    private NotificationPushService pushService;

    @Value("${fooddelivery.redis.worker-interval:5000}")
    private long workerInterval;

    @Value("${fooddelivery.push.batch-size:100}")
    private int batchSize;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notification-push-worker");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<NotificationPushListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);

    @PostConstruct
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("NotificationPushWorker 已启动，处理间隔: {}ms", workerInterval);
            executorService.submit(this::processLoop);
        }
    }

    @PreDestroy
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("NotificationPushWorker 正在停止...");
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("NotificationPushWorker 已停止，已处理: {}, 失败: {}", processedCount.get(), failedCount.get());
        }
    }

    private void processLoop() {
        while (running.get()) {
            try {
                int processed = processBatch();
                if (processed > 0) {
                    log.debug("Worker处理批次完成: {} 条", processed);
                }
                if (running.get()) {
                    TimeUnit.MILLISECONDS.sleep(workerInterval);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("NotificationPushWorker 被中断");
                break;
            } catch (Exception e) {
                log.error("NotificationPushWorker 处理异常: {}", e.getMessage(), e);
                failedCount.incrementAndGet();
            }
        }
    }

    private int processBatch() {
        int count = 0;
        List<NotificationPushService.PushMessage> messages = new ArrayList<>();
        
        for (int i = 0; i < batchSize; i++) {
            NotificationPushService.PushMessage msg = pushService.dequeueFromRedis();
            if (msg == null) {
                break;
            }
            messages.add(msg);
            count++;
        }

        for (NotificationPushService.PushMessage msg : messages) {
            try {
                deliverMessage(msg);
                processedCount.incrementAndGet();
            } catch (Exception e) {
                log.error("消息投递失败: messageId={}, error={}", msg.getMessageId(), e.getMessage());
                failedCount.incrementAndGet();
                retryMessage(msg);
            }
        }

        return count;
    }

    private void deliverMessage(NotificationPushService.PushMessage message) {
        for (NotificationPushListener listener : listeners) {
            try {
                listener.onMessageDelivered(message);
            } catch (Exception e) {
                log.error("监听器处理消息失败: listener={}, messageId={}", 
                          listener.getClass().getSimpleName(), message.getMessageId(), e);
            }
        }
    }

    private void retryMessage(NotificationPushService.PushMessage message) {
        pushService.storeOffline(message.getUserId(), message);
    }

    @Scheduled(fixedRateString = "${fooddelivery.push.monitor-interval:60000}")
    public void monitor() {
        long queueSize = pushService.getRedisQueueSize();
        long total = processedCount.get() + failedCount.get();
        log.info("NotificationPushWorker 监控 - 队列大小: {}, 已处理: {}, 失败: {}", 
                 queueSize, processedCount.get(), failedCount.get());
    }

    public void addListener(NotificationPushListener listener) {
        if (listener != null) {
            listeners.add(listener);
            log.debug("已添加监听器: {}", listener.getClass().getSimpleName());
        }
    }

    public void removeListener(NotificationPushListener listener) {
        if (listener != null) {
            listeners.remove(listener);
            log.debug("已移除监听器: {}", listener.getClass().getSimpleName());
        }
    }

    public long getProcessedCount() {
        return processedCount.get();
    }

    public long getFailedCount() {
        return failedCount.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    public interface NotificationPushListener {
        void onMessageDelivered(NotificationPushService.PushMessage message);
    }
}
