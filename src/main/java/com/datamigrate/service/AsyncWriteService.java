package com.datamigrate.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class AsyncWriteService {

    private final int MAX_QUEUE_SIZE = 10000;
    private final int BATCH_SIZE = 100;
    private final int WORKER_THREADS = 4;
    private final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final BlockingQueue<WriteTask> writeQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private final ExecutorService executorService = Executors.newFixedThreadPool(WORKER_THREADS);
    private final List<Worker> workers = new ArrayList<>();
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final Map<String, WriteResultCallback> callbacks = new ConcurrentHashMap<>();
    
    private volatile boolean running = false;

    public interface WriteResultCallback {
        void onSuccess(WriteTask task);
        void onFailure(WriteTask task, Exception e);
    }

    public static class WriteTask {
        private final String taskId;
        private final Map<String, Object> record;
        private final int retryCount;
        private final int maxRetries;
        private final long createdAt;

        public WriteTask(String taskId, Map<String, Object> record, int maxRetries) {
            this.taskId = taskId;
            this.record = record;
            this.retryCount = 0;
            this.maxRetries = maxRetries;
            this.createdAt = System.currentTimeMillis();
        }

        public WriteTask(String taskId, Map<String, Object> record, int retryCount, int maxRetries) {
            this.taskId = taskId;
            this.record = record;
            this.retryCount = retryCount;
            this.maxRetries = maxRetries;
            this.createdAt = System.currentTimeMillis();
        }

        public String getTaskId() { return taskId; }
        public Map<String, Object> getRecord() { return record; }
        public int getRetryCount() { return retryCount; }
        public int getMaxRetries() { return maxRetries; }
        public long getCreatedAt() { return createdAt; }
    }

    public static class WriteResult {
        private final boolean success;
        private final Exception exception;
        private final WriteTask task;

        public WriteResult(WriteTask task, boolean success) {
            this.task = task;
            this.success = success;
            this.exception = null;
        }

        public WriteResult(WriteTask task, boolean success, Exception exception) {
            this.task = task;
            this.success = success;
            this.exception = exception;
        }

        public boolean isSuccess() { return success; }
        public Exception getException() { return exception; }
        public WriteTask getTask() { return task; }
    }

    public void start() {
        if (!running) {
            running = true;
            for (int i = 0; i < WORKER_THREADS; i++) {
                Worker worker = new Worker("WriteWorker-" + i);
                workers.add(worker);
                executorService.submit(worker);
            }
            log.info("异步写入服务已启动, Worker线程数: {}, 队列容量: {}", WORKER_THREADS, MAX_QUEUE_SIZE);
        }
    }

    public void stop() {
        running = false;
        workers.clear();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("异步写入服务已停止");
    }

    public boolean submitTask(WriteTask task) {
        try {
            boolean offered = writeQueue.offer(task, 5, TimeUnit.SECONDS);
            if (!offered) {
                log.warn("写入队列已满，任务提交超时: taskId={}", task.getTaskId());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void submitTasks(String taskId, List<Map<String, Object>> records, int maxRetries,
                            WriteResultCallback callback) {
        callbacks.put(taskId, callback);
        for (Map<String, Object> record : records) {
            submitTask(new WriteTask(taskId, record, maxRetries));
        }
    }

    public void registerCallback(String taskId, WriteResultCallback callback) {
        callbacks.put(taskId, callback);
    }

    private class Worker implements Runnable {
        private final String name;

        public Worker(String name) {
            this.name = name;
        }

        @Override
        public void run() {
            log.info("{} 开始运行", name);
            List<WriteTask> batch = new ArrayList<>(BATCH_SIZE);
            
            while (running || !writeQueue.isEmpty()) {
                try {
                    batch.clear();
                    writeQueue.drainTo(batch, BATCH_SIZE);
                    
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

        private void processBatch(List<WriteTask> batch) {
            for (WriteTask task : batch) {
                WriteResultCallback callback = callbacks.get(task.getTaskId());
                try {
                    boolean success = executeWrite(task);
                    if (success) {
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

        private boolean executeWrite(WriteTask task) {
            return true;
        }

        private void handleRetry(WriteTask task, Exception e) {
            WriteResultCallback callback = callbacks.get(task.getTaskId());
            
            if (task.getRetryCount() < task.getMaxRetries()) {
                log.warn("写入失败，准备重试: taskId={}, retryCount={}/{}", 
                    task.getTaskId(), task.getRetryCount() + 1, task.getMaxRetries());
                
                WriteTask retryTask = new WriteTask(
                    task.getTaskId(), 
                    task.getRecord(), 
                    task.getRetryCount() + 1, 
                    task.getMaxRetries()
                );
                
                try {
                    Thread.sleep(getRetryDelay(task.getRetryCount()));
                    if (executeWrite(task)) {
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

    public int getQueueSize() {
        return writeQueue.size();
    }

    public int getSuccessCount() {
        return successCount.get();
    }

    public int getFailCount() {
        return failCount.get();
    }

    public boolean isRunning() {
        return running;
    }

    public void resetCounters() {
        successCount.set(0);
        failCount.set(0);
    }
}
