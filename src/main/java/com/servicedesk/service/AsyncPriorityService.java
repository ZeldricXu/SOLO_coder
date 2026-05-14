package com.servicedesk.service;

import com.servicedesk.config.ServiceDeskProperties;
import com.servicedesk.dto.CreateTicketRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncPriorityService {

    private final PriorityService priorityService;
    private final ServiceDeskProperties properties;

    private ExecutorService executorService;
    private final Queue<PriorityTask> taskQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger failedTasks = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        int poolSize = properties.getPriorityAsync().getThreadPoolSize();
        this.executorService = Executors.newFixedThreadPool(poolSize);
    }

    public void shutdown() {
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    public Future<String> evaluatePriorityAsync(CreateTicketRequest request, Consumer<String> onComplete) {
        return executorService.submit(() -> {
            int maxRetries = properties.getPriorityAsync().getMaxRetries();
            long retryInterval = properties.getPriorityAsync().getRetryIntervalMs();
            Exception lastException = null;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    String priority = priorityService.evaluatePriority(request);
                    if (onComplete != null) {
                        onComplete.accept(priority);
                    }
                    log.info("异步优先级评估完成: {}", priority);
                    return priority;
                } catch (Exception e) {
                    lastException = e;
                    log.warn("优先级评估失败 (第{}次尝试): {}", attempt, e.getMessage());
                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(retryInterval);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("优先级评估中断", ie);
                        }
                    }
                }
            }
            failedTasks.incrementAndGet();
            log.error("优先级评估在{}次尝试后全部失败", maxRetries);
            if (lastException != null) {
                throw new RuntimeException("优先级评估失败", lastException);
            }
            throw new RuntimeException("优先级评估失败");
        });
    }

    public void submitTask(CreateTicketRequest request, Consumer<String> onComplete, Consumer<Exception> onError) {
        PriorityTask task = new PriorityTask(request, onComplete, onError);
        taskQueue.offer(task);
        executorService.submit(() -> executeTask(task));
    }

    private void executeTask(PriorityTask task) {
        int maxRetries = properties.getPriorityAsync().getMaxRetries();
        long retryInterval = properties.getPriorityAsync().getRetryIntervalMs();
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String priority = priorityService.evaluatePriority(task.request);
                task.attempts = attempt;
                task.success = true;
                if (task.onComplete != null) {
                    task.onComplete.accept(priority);
                }
                return;
            } catch (Exception e) {
                lastException = e;
                task.attempts = attempt;
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryInterval);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        task.success = false;
        failedTasks.incrementAndGet();
        if (task.onError != null && lastException != null) {
            task.onError.accept(lastException);
        }
    }

    public int getQueueSize() {
        return taskQueue.size();
    }

    public int getFailedTaskCount() {
        return failedTasks.get();
    }

    public void resetFailedCount() {
        failedTasks.set(0);
    }

    public static class PriorityTask {
        public final CreateTicketRequest request;
        public final Consumer<String> onComplete;
        public final Consumer<Exception> onError;
        public int attempts = 0;
        public boolean success = false;

        public PriorityTask(CreateTicketRequest request, Consumer<String> onComplete, Consumer<Exception> onError) {
            this.request = request;
            this.onComplete = onComplete;
            this.onError = onError;
        }
    }
}
