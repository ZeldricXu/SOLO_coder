package com.assetinventory.util;

import com.assetinventory.config.DetectionConfig;
import com.assetinventory.entity.Asset;
import com.assetinventory.entity.InventoryDifference;
import com.assetinventory.entity.InventoryRecord;
import com.assetinventory.service.AssetService;
import com.assetinventory.service.DifferenceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Component
public class AsyncDifferenceDetector {

    private static final Logger logger = LoggerFactory.getLogger(AsyncDifferenceDetector.class);

    private final AssetService assetService;
    private final DifferenceService differenceService;
    private final DetectionConfig detectionConfig;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private final List<DetectionResult> completedResults = new CopyOnWriteArrayList<>();
    private final AtomicInteger retryCount = new AtomicInteger(0);

    private volatile boolean running = false;
    private final List<Thread> workerThreads = new ArrayList<>();

    private final ExecutorService callbackExecutor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, CompletableFuture<DetectionResult>> pendingFutures = new ConcurrentHashMap<>();

    public static class DetectionTask {
        private final String taskId;
        private final String planId;
        private final String taskRefId;
        private final InventoryRecord record;
        private final int maxRetries;
        private final int currentRetry;
        private final long submitTime;
        private final String callbackId;

        public DetectionTask(String taskId, String planId, String taskRefId,
                            InventoryRecord record, int maxRetries) {
            this(taskId, planId, taskRefId, record, maxRetries, 0, System.currentTimeMillis(), null);
        }

        public DetectionTask(String taskId, String planId, String taskRefId,
                            InventoryRecord record, int maxRetries, int currentRetry,
                            long submitTime, String callbackId) {
            this.taskId = taskId;
            this.planId = planId;
            this.taskRefId = taskRefId;
            this.record = record;
            this.maxRetries = maxRetries;
            this.currentRetry = currentRetry;
            this.submitTime = submitTime;
            this.callbackId = callbackId;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getPlanId() {
            return planId;
        }

        public String getTaskRefId() {
            return taskRefId;
        }

        public InventoryRecord getRecord() {
            return record;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public int getCurrentRetry() {
            return currentRetry;
        }

        public long getSubmitTime() {
            return submitTime;
        }

        public String getCallbackId() {
            return callbackId;
        }

        public DetectionTask withIncrementedRetry() {
            return new DetectionTask(
                    taskId,
                    planId,
                    taskRefId,
                    record,
                    maxRetries,
                    currentRetry + 1,
                    submitTime,
                    callbackId
            );
        }
    }

    public static class DetectionResult {
        private final String taskId;
        private final InventoryRecord record;
        private final List<InventoryDifference> differences;
        private final boolean success;
        private final String errorMessage;
        private final int retryAttempts;

        public DetectionResult(String taskId, InventoryRecord record,
                              List<InventoryDifference> differences, boolean success,
                              String errorMessage, int retryAttempts) {
            this.taskId = taskId;
            this.record = record;
            this.differences = differences;
            this.success = success;
            this.errorMessage = errorMessage;
            this.retryAttempts = retryAttempts;
        }

        public String getTaskId() {
            return taskId;
        }

        public InventoryRecord getRecord() {
            return record;
        }

        public List<InventoryDifference> getDifferences() {
            return differences;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public int getRetryAttempts() {
            return retryAttempts;
        }

        public boolean hasDifferences() {
            return differences != null && !differences.isEmpty();
        }
    }

    @Autowired
    public AsyncDifferenceDetector(AssetService assetService,
                                   DifferenceService differenceService,
                                   DetectionConfig detectionConfig,
                                   StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper) {
        this.assetService = assetService;
        this.differenceService = differenceService;
        this.detectionConfig = detectionConfig;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (detectionConfig.isEnabled()) {
            start();
        }
    }

    @PreDestroy
    public void destroy() {
        shutdown();
    }

    public void start() {
        if (!running) {
            running = true;

            int threadCount = detectionConfig.getWorker().getThreadCount();
            for (int i = 0; i < threadCount; i++) {
                Thread worker = new Thread(this::runWorker,
                        "AsyncDifferenceDetector-Worker-" + i);
                worker.setDaemon(true);
                worker.start();
                workerThreads.add(worker);
            }

            logger.info("AsyncDifferenceDetector started with {} worker threads", threadCount);
        }
    }

    public void stop() {
        running = false;
        for (Thread worker : workerThreads) {
            worker.interrupt();
        }
        callbackExecutor.shutdown();
        logger.info("AsyncDifferenceDetector stopped");
    }

    public void shutdown() {
        stop();
        try {
            if (!callbackExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                callbackExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            callbackExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("AsyncDifferenceDetector shutdown complete");
    }

    private void runWorker() {
        while (running) {
            try {
                DetectionTask task = pollFromRedis();
                if (task != null) {
                    String processingKey = task.getTaskId();
                    try {
                        markProcessing(task);
                        processTask(task);
                        removeProcessing(task);
                    } catch (Exception e) {
                        logger.error("Error processing task: {}", task.getTaskId(), e);
                        removeProcessing(task);
                        handleFailure(task, e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error in worker thread", e);
            }
        }
    }

    private DetectionTask pollFromRedis() throws InterruptedException {
        long pollTimeoutMs = detectionConfig.getWorker().getPollTimeoutMs();
        long timeoutSeconds = Math.max(1, pollTimeoutMs / 1000);

        String queueName = detectionConfig.getQueueName();
        List<String> result = redisTemplate.opsForList().rightPop(queueName, timeoutSeconds, TimeUnit.SECONDS);

        if (result == null || result.isEmpty()) {
            return null;
        }

        String json = result.get(1);
        return deserializeTask(json);
    }

    private void pushToRedis(DetectionTask task) {
        String json = serializeTask(task);
        redisTemplate.opsForList().leftPush(detectionConfig.getQueueName(), json);
    }

    private void pushToRetryQueue(DetectionTask task) {
        String json = serializeTask(task);
        redisTemplate.opsForList().leftPush(detectionConfig.getRetryQueueName(), json);
    }

    private void markProcessing(DetectionTask task) {
        redisTemplate.opsForSet().add(detectionConfig.getProcessingSet(), task.getTaskId());
    }

    private void removeProcessing(DetectionTask task) {
        redisTemplate.opsForSet().remove(detectionConfig.getProcessingSet(), task.getTaskId());
    }

    private String serializeTask(DetectionTask task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize DetectionTask", e);
        }
    }

    private DetectionTask deserializeTask(String json) {
        try {
            return objectMapper.readValue(json, DetectionTask.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize DetectionTask", e);
        }
    }

    private void processTask(DetectionTask task) {
        logger.info("Processing detection task: {}, retry: {}", task.getTaskId(), task.getCurrentRetry());

        try {
            DetectionResult result = performDetection(task);
            completedResults.add(result);

            invokeCallback(task, result);

            logger.info("Detection task completed: {}, success: {}, diffs: {}",
                    task.getTaskId(), result.isSuccess(),
                    result.hasDifferences() ? result.getDifferences().size() : 0);

        } catch (Exception e) {
            logger.error("Error processing detection task: {}", task.getTaskId(), e);
            handleFailure(task, e);
        }
    }

    private void handleFailure(DetectionTask task, Exception e) {
        if (task.getCurrentRetry() < task.getMaxRetries()) {
            retryCount.incrementAndGet();
            DetectionTask retryTask = task.withIncrementedRetry();

            try {
                Thread.sleep(detectionConfig.getRetry().getRetryDelaySeconds() * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            pushToRetryQueue(retryTask);
            logger.info("Scheduled retry for task: {}, attempt: {}",
                    task.getTaskId(), task.getCurrentRetry() + 1);
        } else {
            DetectionResult failedResult = new DetectionResult(
                    task.getTaskId(),
                    task.getRecord(),
                    new ArrayList<>(),
                    false,
                    e.getMessage(),
                    task.getCurrentRetry()
            );
            completedResults.add(failedResult);
            invokeCallback(task, failedResult);
        }
    }

    private void invokeCallback(DetectionTask task, DetectionResult result) {
        if (task.getCallbackId() != null) {
            CompletableFuture<DetectionResult> future = pendingFutures.remove(task.getCallbackId());
            if (future != null) {
                if (result.isSuccess()) {
                    future.complete(result);
                } else {
                    future.completeExceptionally(new RuntimeException(result.getErrorMessage()));
                }
            }
        }
    }

    private DetectionResult performDetection(DetectionTask task) {
        InventoryRecord record = task.getRecord();
        List<InventoryDifference> differences = new ArrayList<>();

        Asset asset = assetService.getAssetByIdOrThrow(record.getAssetId());

        boolean quantityMatch = record.getCountQuantity() == asset.getAssetQuantity();
        boolean locationMatch = record.getCountLocation().equals(asset.getAssetLocation());

        if (!quantityMatch) {
            InventoryDifference diff = differenceService.createDifference(
                    task.getPlanId() != null ? task.getPlanId() : record.getCountId(),
                    task.getTaskRefId() != null ? task.getTaskRefId() : record.getTaskId(),
                    asset.getAssetId(),
                    asset.getAssetQuantity(),
                    record.getCountQuantity()
            );
            differences.add(diff);
        }

        if (!locationMatch) {
            InventoryDifference diff = differenceService.createDifference(
                    task.getPlanId() != null ? task.getPlanId() : record.getCountId(),
                    task.getTaskRefId() != null ? task.getTaskRefId() : record.getTaskId(),
                    asset.getAssetId(),
                    asset.getAssetQuantity(),
                    record.getCountQuantity()
            );
            differences.add(diff);
        }

        return new DetectionResult(
                task.getTaskId(),
                record,
                differences,
                true,
                null,
                task.getCurrentRetry()
        );
    }

    public void submitTask(String planId, String taskId) {
        String detectionTaskId = "detect_" + System.currentTimeMillis() + "_" + taskId;

        DetectionTask task = new DetectionTask(
                detectionTaskId,
                planId,
                taskId,
                null,
                detectionConfig.getRetry().getMaxRetries()
        );

        pushToRedis(task);

        logger.info("Submitted detection task: {}, plan: {}, task: {}",
                detectionTaskId, planId, taskId);
    }

    public CompletableFuture<DetectionResult> submitDetection(InventoryRecord record) {
        return submitDetection(record, detectionConfig.getRetry().getMaxRetries(), null);
    }

    public CompletableFuture<DetectionResult> submitDetection(InventoryRecord record,
                                                              int maxRetries,
                                                              Consumer<DetectionResult> callback) {
        CompletableFuture<DetectionResult> future = new CompletableFuture<>();
        String callbackId = "cb_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();

        if (callback != null) {
            pendingFutures.put(callbackId, future);
            future.whenComplete((result, ex) -> {
                if (callback != null) {
                    callbackExecutor.execute(() -> {
                        try {
                            callback.accept(result);
                        } catch (Exception e) {
                            logger.error("Error in callback execution", e);
                        }
                    });
                }
            });
        }

        String taskId = "detect_" + System.currentTimeMillis() + "_" +
                (record.getCountId() != null ? record.getCountId() : "record");

        DetectionTask task = new DetectionTask(
                taskId,
                null,
                record.getTaskId(),
                record,
                maxRetries,
                0,
                System.currentTimeMillis(),
                callback != null ? callbackId : null
        );

        pushToRedis(task);

        if (callback == null) {
            future.completeAsync(() -> waitForCompletion(taskId), callbackExecutor);
        }

        return future;
    }

    private DetectionResult waitForCompletion(String taskId) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = 60000;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            for (DetectionResult result : completedResults) {
                if (taskId.equals(result.getTaskId())) {
                    return result;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for detection result", e);
            }
        }

        throw new RuntimeException("Timeout waiting for detection result: " + taskId);
    }

    public int getQueueSize() {
        Long size = redisTemplate.opsForList().size(detectionConfig.getQueueName());
        return size != null ? size.intValue() : 0;
    }

    public int getRetryQueueSize() {
        Long size = redisTemplate.opsForList().size(detectionConfig.getRetryQueueName());
        return size != null ? size.intValue() : 0;
    }

    public int getProcessingCount() {
        Long size = redisTemplate.opsForSet().size(detectionConfig.getProcessingSet());
        return size != null ? size.intValue() : 0;
    }

    public int getCompletedCount() {
        return completedResults.size();
    }

    public int getRetryCount() {
        return retryCount.get();
    }

    public List<DetectionResult> getCompletedResults() {
        return new ArrayList<>(completedResults);
    }

    public void clearCompletedResults() {
        completedResults.clear();
    }

    public void reset() {
        redisTemplate.delete(detectionConfig.getQueueName());
        redisTemplate.delete(detectionConfig.getRetryQueueName());
        redisTemplate.delete(detectionConfig.getProcessingSet());
        completedResults.clear();
        retryCount.set(0);
        pendingFutures.clear();
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isEnabled() {
        return detectionConfig.isEnabled();
    }

    public int getWorkerThreadCount() {
        return detectionConfig.getWorker().getThreadCount();
    }

    public Set<String> getProcessingTasks() {
        return redisTemplate.opsForSet().members(detectionConfig.getProcessingSet());
    }
}
