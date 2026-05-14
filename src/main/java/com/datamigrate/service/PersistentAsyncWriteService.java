package com.datamigrate.service;

import com.datamigrate.config.RedisConfig;
import com.datamigrate.queue.PersistentWriteQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersistentAsyncWriteService {

    private final PersistentWriteQueue persistentQueue;
    private final RedisConfig redisConfig;

    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int DEFAULT_WORKER_THREADS = 4;
    private static final long DEFAULT_SHUTDOWN_TIMEOUT = 30;

    private final ExecutorService executorService = 
        Executors.newFixedThreadPool(DEFAULT_WORKER_THREADS);
    private final List<PersistentWriteWorker> workers = new ArrayList<>();
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final Map<String, AsyncWriteService.WriteResultCallback> callbacks = new ConcurrentHashMap<>();
    
    private volatile boolean running = false;

    public interface WriteHandler {
        boolean write(Map<String, Object> record) throws Exception;
    }

    @PostConstruct
    public void init() {
        start();
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    public void start() {
        if (!running) {
            running = true;
            for (int i = 0; i < DEFAULT_WORKER_THREADS; i++) {
                PersistentWriteWorker worker = new PersistentWriteWorker(
                    "PersistentWriteWorker-" + i);
                workers.add(worker);
                executorService.submit(worker);
            }
            log.info("持久化异步写入服务已启动, Worker线程数: {}, 队列类型: {}",
                DEFAULT_WORKER_THREADS, 
                redisConfig.isEnabled() ? "Redis" : "Memory");
        }
    }

    public void stop() {
        running = false;
        workers.clear();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(DEFAULT_SHUTDOWN_TIMEOUT, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("持久化异步写入服务已停止");
    }

    public boolean submitTask(String taskId, Map<String, Object> record, int maxRetries) {
        AsyncWriteService.WriteTask task = new AsyncWriteService.WriteTask(
            taskId, record, 0, maxRetries);
        return persistentQueue.offer(task);
    }

    public void submitTasks(String taskId, List<Map<String, Object>> records,
                             int maxRetries, AsyncWriteService.WriteResultCallback callback) {
        callbacks.put(taskId, callback);
        for (Map<String, Object> record : records) {
            submitTask(taskId, record, maxRetries);
        }
    }

    public void registerCallback(String taskId, AsyncWriteService.WriteResultCallback callback) {
        callbacks.put(taskId, callback);
    }

    private class PersistentWriteWorker implements Runnable {
        private final String name;
        private final Random random = new Random();

        public PersistentWriteWorker(String name) {
            this.name = name;
        }

        @Override
        public void run() {
            log.info("{} 开始运行", name);
            List<AsyncWriteService.WriteTask> batch = new ArrayList<>(DEFAULT_BATCH_SIZE);

            while (running || !isAllQueuesEmpty()) {
                try {
                    batch.clear();
                    
                    for (int i = 0; i < DEFAULT_BATCH_SIZE; i++) {
                        AsyncWriteService.WriteTask task = persistentQueue.drainTo("", 1).stream().findFirst().orElse(null);
                        if (task != null) {
                            batch.add(task);
                        }
                    }

                    if (batch.isEmpty()) {
                        if (running) {
                            Thread.sleep(100);
                        }
                        continue;
                    }

                    processBatch(batch);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("{} 处理批次异常", name, e);
                }
            }
            log.info("{} 已停止", name);
        }

        private void processBatch(List<AsyncWriteService.WriteTask> batch) {
            for (AsyncWriteService.WriteTask task : batch) {
                AsyncWriteService.WriteResultCallback callback = callbacks.get(task.getTaskId());
                try {
                    if (simulateWrite(task)) {
                        successCount.incrementAndGet();
                        if (callback != null) {
                            callback.onSuccess(task);
                        }
                    } else {
                        handleRetry(task, new Exception("写入失败"));
                    }
                } catch (Exception e) {
                    handleRetry(task, e);
                }
            }
        }

        private boolean simulateWrite(AsyncWriteService.WriteTask task) {
            return true;
        }

        private void handleRetry(AsyncWriteService.WriteTask task, Exception e) {
            AsyncWriteService.WriteResultCallback callback = callbacks.get(task.getTaskId());

            if (task.getRetryCount() < task.getMaxRetries()) {
                log.warn("写入失败，准备重试: taskId={}, retryCount={}/{}",
                    task.getTaskId(), task.getRetryCount() + 1, task.getMaxRetries());

                AsyncWriteService.WriteTask retryTask = new AsyncWriteService.WriteTask(
                    task.getTaskId(),
                    task.getRecord(),
                    task.getRetryCount() + 1,
                    task.getMaxRetries()
                );

                try {
                    Thread.sleep(getRetryDelay(task.getRetryCount()));
                    if (simulateWrite(task)) {
                        successCount.incrementAndGet();
                        if (callback != null) {
                            callback.onSuccess(retryTask);
                        }
                        return;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }

                handleRetry(retryTask, e);
            } else {
                failCount.incrementAndGet();
                log.error("写入最终失败: taskId={}, 已达最大重试次数{}",
                    task.getTaskId(), task.getMaxRetries());
                if (callback != null) {
                    callback.onFailure(task, e);
                }
            }
        }

        private long getRetryDelay(int retryCount) {
            return (long) Math.pow(2, retryCount) * 1000;
        }
    }

    private boolean isAllQueuesEmpty() {
        return true;
    }

    public long getQueueSize(String taskId) {
        return persistentQueue.size(taskId);
    }

    public boolean isQueueEmpty(String taskId) {
        return persistentQueue.isEmpty(taskId);
    }

    public void clearQueue(String taskId) {
        persistentQueue.clear(taskId);
    }

    public int getSuccessCount() {
        return successCount.get();
    }

    public int getFailCount() {
        return failCount.get();
    }

    public void resetCounters() {
        successCount.set(0);
        failCount.set(0);
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isRedisEnabled() {
        return redisConfig.isEnabled();
    }
}
