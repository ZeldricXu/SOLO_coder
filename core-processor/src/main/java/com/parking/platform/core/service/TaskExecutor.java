package com.parking.platform.core.service;

import com.parking.platform.common.constant.Constants;
import com.parking.platform.common.exception.TimeoutException;
import com.parking.platform.core.entity.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

@Service
public class TaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutor.class);

    private final ExecutorService executorService;
    private final EventEmitter eventEmitter;
    private final TaskHandlerRegistry handlerRegistry;

    public TaskExecutor(EventEmitter eventEmitter, TaskHandlerRegistry handlerRegistry) {
        this.executorService = Executors.newCachedThreadPool();
        this.eventEmitter = eventEmitter;
        this.handlerRegistry = handlerRegistry;
    }

    public void executeTask(Task task) {
        executorService.submit(() -> runTask(task));
    }

    private void runTask(Task task) {
        try {
            task.start();
            log.info("Starting task execution: {}", task.getId());

            TaskHandler handler = handlerRegistry.getHandler(task.getType());
            if (handler != null) {
                executeWithRetry(task, handler);
            } else {
                executeDefaultTask(task);
            }

        } catch (Exception e) {
            log.error("Task execution failed: {}", task.getId(), e);
            handleTaskFailure(task, e);
        }
    }

    private void executeWithRetry(Task task, TaskHandler handler) throws Exception {
        int maxAttempts = task.getMaxRetries() + 1;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (attempt > 1) {
                    log.warn("Retrying task {} (attempt {}/{})", task.getId(), attempt, maxAttempts);
                    task.incrementRetry();
                    Thread.sleep(getBackoffDelay(attempt));
                }

                task.updateProgress(Constants.PHASE_PROCESSING, 0.3);
                Map<String, Object> result = executeWithTimeout(task, handler);
                task.updateProgress(Constants.PHASE_FINALIZING, 0.9);

                task.complete(result);
                eventEmitter.emit(Constants.EVENT_TASK_COMPLETED, task);
                log.info("Task completed successfully: {}", task.getId());
                return;

            } catch (TimeoutException e) {
                lastException = e;
                log.warn("Task timed out: {}, attempt: {}", task.getId(), attempt);
                if (attempt == maxAttempts || !task.canRetry()) {
                    throw e;
                }
            } catch (Exception e) {
                lastException = e;
                log.warn("Task failed: {}, attempt: {}", task.getId(), attempt, e);
                if (attempt == maxAttempts || !task.canRetry()) {
                    throw e;
                }
            }
        }

        if (lastException != null) {
            throw lastException;
        }
    }

    private Map<String, Object> executeWithTimeout(Task task, TaskHandler handler) throws Exception {
        Future<Map<String, Object>> future = executorService.submit(() ->
                handler.execute(task.getPayload(), task.getConfig())
        );

        try {
            Long timeout = task.getTimeout();
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("Task execution timed out");
        }
    }

    private void executeDefaultTask(Task task) {
        log.info("Executing default handler for task type: {}", task.getType());

        Map<String, Object> result = new HashMap<>();
        result.put("processed", true);
        result.put("taskId", task.getId());
        result.put("timestamp", System.currentTimeMillis());

        try {
            Thread.sleep(500);
            task.updateProgress(Constants.PHASE_PROCESSING, 0.5);
            Thread.sleep(500);
            task.updateProgress(Constants.PHASE_FINALIZING, 0.9);
            Thread.sleep(200);

            task.complete(result);
            eventEmitter.emit(Constants.EVENT_TASK_COMPLETED, task);
            log.info("Default task completed: {}", task.getId());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Task interrupted", e);
        }
    }

    private void handleTaskFailure(Task task, Exception e) {
        String errorDetail = e.getMessage() != null ? e.getMessage() : "Unknown error";
        task.fail(errorDetail);
        eventEmitter.emit(Constants.EVENT_TASK_FAILED, task);
        log.error("Task failed permanently: {}", task.getId(), e);
    }

    private long getBackoffDelay(int attempt) {
        return (long) Math.pow(2, attempt) * 1000;
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
