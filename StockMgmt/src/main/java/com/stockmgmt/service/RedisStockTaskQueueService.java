package com.stockmgmt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmgmt.dto.InboundRequest;
import com.stockmgmt.dto.OutboundRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RedisStockTaskQueueService {

    private static final Logger logger = LoggerFactory.getLogger(RedisStockTaskQueueService.class);

    private static final String QUEUE_KEY = "stock:task:queue";
    private static final String PROCESSING_KEY = "stock:task:processing";
    private static final String RESULT_KEY_PREFIX = "stock:task:result:";
    private static final String TASK_ID_PREFIX = "TASK_";

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InboundOutboundService inboundOutboundService;

    @Value("${stock.task.max-retry:3}")
    private int maxRetry;

    @Value("${stock.task.retry-delay:1000}")
    private long retryDelay;

    @Value("${stock.task.worker-count:2}")
    private int workerCount;

    @Value("${stock.task.processing-timeout:300000}")
    private long processingTimeout;

    private final ExecutorService workerPool = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Future<?>> workerFutures = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, StockTask> localTaskStore = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        startWorkers();
    }

    public void startWorkers() {
        if (running.compareAndSet(false, true)) {
            logger.info("启动库存更新任务Worker，数量: {}", workerCount);
            for (int i = 0; i < workerCount; i++) {
                Future<?> future = workerPool.submit(new TaskWorker(i));
                workerFutures.add(future);
            }
        }
    }

    public void stopWorkers() {
        if (running.compareAndSet(true, false)) {
            logger.info("停止库存更新任务Worker");
            for (Future<?> future : workerFutures) {
                future.cancel(true);
            }
            workerFutures.clear();
        }
    }

    public String submitInboundTask(InboundRequest request) {
        StockTask task = new StockTask();
        task.setTaskId(generateTaskId());
        task.setTaskType(TaskType.INBOUND);
        task.setInboundRequest(request);
        task.setStatus(TaskStatus.PENDING);
        task.setRetryCount(0);
        task.setSubmittedAt(LocalDateTime.now());

        return submitTask(task);
    }

    public String submitOutboundTask(OutboundRequest request) {
        StockTask task = new StockTask();
        task.setTaskId(generateTaskId());
        task.setTaskType(TaskType.OUTBOUND);
        task.setOutboundRequest(request);
        task.setStatus(TaskStatus.PENDING);
        task.setRetryCount(0);
        task.setSubmittedAt(LocalDateTime.now());

        return submitTask(task);
    }

    private String submitTask(StockTask task) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().rightPush(QUEUE_KEY, taskJson);
            localTaskStore.put(task.getTaskId(), task);

            logger.info("库存更新任务已提交到Redis队列: taskId={}, type={}",
                    task.getTaskId(), task.getTaskType());

            return task.getTaskId();
        } catch (Exception e) {
            logger.error("提交任务到Redis队列失败，使用本地队列", e);
            localTaskStore.put(task.getTaskId(), task);
            submitToLocalQueue(task);
            return task.getTaskId();
        }
    }

    public Optional<StockTask> getTaskStatus(String taskId) {
        StockTask localTask = localTaskStore.get(taskId);
        if (localTask != null) {
            return Optional.of(localTask);
        }

        String resultJson = redisTemplate.opsForValue().get(RESULT_KEY_PREFIX + taskId);
        if (resultJson != null) {
            try {
                return Optional.of(objectMapper.readValue(resultJson, StockTask.class));
            } catch (Exception e) {
                logger.warn("解析任务结果失败: taskId={}", taskId, e);
            }
        }

        return Optional.empty();
    }

    public void recoverFailedTasks() {
        logger.info("恢复失败的库存更新任务...");
        Set<String> processingKeys = redisTemplate.keys(PROCESSING_KEY + ":*");
        if (processingKeys == null) return;

        LocalDateTime now = LocalDateTime.now();
        for (String key : processingKeys) {
            try {
                String taskJson = redisTemplate.opsForValue().get(key);
                if (taskJson == null) continue;

                StockTask task = objectMapper.readValue(taskJson, StockTask.class);
                if (task.getProcessingStartedAt() != null) {
                    long processingMillis = java.time.Duration.between(
                            task.getProcessingStartedAt(), now).toMillis();
                    if (processingMillis > processingTimeout) {
                        logger.warn("任务处理超时，重新入队: taskId={}", task.getTaskId());
                        redisTemplate.delete(key);
                        redisTemplate.opsForList().leftPush(QUEUE_KEY, taskJson);
                    }
                }
            } catch (Exception e) {
                logger.warn("恢复任务失败: key={}", key, e);
            }
        }
    }

    public long getPendingTaskCount() {
        Long count = redisTemplate.opsForList().size(QUEUE_KEY);
        return count != null ? count : 0;
    }

    public long getProcessingTaskCount() {
        Set<String> keys = redisTemplate.keys(PROCESSING_KEY + ":*");
        return keys != null ? keys.size() : 0;
    }

    private void submitToLocalQueue(StockTask task) {
        workerPool.submit(() -> processTask(task));
    }

    private String generateTaskId() {
        return TASK_ID_PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) +
                "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private class TaskWorker implements Runnable {
        private final int workerId;

        public TaskWorker(int workerId) {
            this.workerId = workerId;
        }

        @Override
        public void run() {
            logger.info("TaskWorker-{} 已启动", workerId);
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    String taskJson = redisTemplate.opsForList()
                            .leftPop(QUEUE_KEY, 5, TimeUnit.SECONDS);

                    if (taskJson == null) {
                        continue;
                    }

                    StockTask task = objectMapper.readValue(taskJson, StockTask.class);
                    logger.info("TaskWorker-{} 开始处理任务: taskId={}", workerId, task.getTaskId());

                    String processingKey = PROCESSING_KEY + ":" + task.getTaskId();
                    task.setStatus(TaskStatus.PROCESSING);
                    task.setProcessingStartedAt(LocalDateTime.now());
                    redisTemplate.opsForValue().set(processingKey, objectMapper.writeValueAsString(task));

                    localTaskStore.put(task.getTaskId(), task);

                    processTask(task);

                    redisTemplate.delete(processingKey);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("TaskWorker-{} 处理任务异常", workerId, e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            logger.info("TaskWorker-{} 已停止", workerId);
        }
    }

    private void processTask(StockTask task) {
        try {
            Object result = null;

            if (task.getTaskType() == TaskType.INBOUND) {
                result = inboundOutboundService.inbound(task.getInboundRequest());
            } else if (task.getTaskType() == TaskType.OUTBOUND) {
                result = inboundOutboundService.outbound(task.getOutboundRequest());
            }

            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(LocalDateTime.now());
            task.setResult(result);

            saveTaskResult(task);

            logger.info("任务执行成功: taskId={}, type={}", task.getTaskId(), task.getTaskType());

        } catch (Exception e) {
            logger.warn("任务执行失败: taskId={}, attempt={}, error={}",
                    task.getTaskId(), task.getRetryCount() + 1, e.getMessage());

            handleTaskFailure(task, e);
        }

        localTaskStore.put(task.getTaskId(), task);
    }

    private void handleTaskFailure(StockTask task, Exception e) {
        int newRetryCount = task.getRetryCount() + 1;
        task.setRetryCount(newRetryCount);
        task.setErrorMessage(e.getMessage());

        if (newRetryCount >= maxRetry) {
            task.setStatus(TaskStatus.FAILED);
            task.setFailedAt(LocalDateTime.now());
            saveTaskResult(task);
            logger.error("任务最终失败: taskId={}, 已达到最大重试次数 {}", task.getTaskId(), maxRetry);
        } else {
            try {
                long delay = retryDelay * newRetryCount;
                task.setStatus(TaskStatus.PENDING);

                String taskJson = objectMapper.writeValueAsString(task);
                redisTemplate.opsForList().rightPush(QUEUE_KEY, taskJson);

                logger.info("任务重新入队: taskId={}, 下次重试延迟 {}ms", task.getTaskId(), delay);
            } catch (Exception ex) {
                logger.error("任务重新入队失败", ex);
                task.setStatus(TaskStatus.FAILED);
                saveTaskResult(task);
            }
        }
    }

    private void saveTaskResult(StockTask task) {
        try {
            String resultJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForValue().set(RESULT_KEY_PREFIX + task.getTaskId(), resultJson, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            logger.error("保存任务结果失败: taskId={}", task.getTaskId(), e);
        }
    }

    public enum TaskType {
        INBOUND, OUTBOUND, TRANSFER
    }

    public enum TaskStatus {
        PENDING, PROCESSING, COMPLETED, FAILED
    }

    public static class StockTask {
        private String taskId;
        private TaskType taskType;
        private TaskStatus status;
        private InboundRequest inboundRequest;
        private OutboundRequest outboundRequest;
        private Object result;
        private String errorMessage;
        private int retryCount;
        private LocalDateTime submittedAt;
        private LocalDateTime processingStartedAt;
        private LocalDateTime completedAt;
        private LocalDateTime failedAt;

        public String getTaskId() {
            return taskId;
        }

        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }

        public TaskType getTaskType() {
            return taskType;
        }

        public void setTaskType(TaskType taskType) {
            this.taskType = taskType;
        }

        public TaskStatus getStatus() {
            return status;
        }

        public void setStatus(TaskStatus status) {
            this.status = status;
        }

        public InboundRequest getInboundRequest() {
            return inboundRequest;
        }

        public void setInboundRequest(InboundRequest inboundRequest) {
            this.inboundRequest = inboundRequest;
        }

        public OutboundRequest getOutboundRequest() {
            return outboundRequest;
        }

        public void setOutboundRequest(OutboundRequest outboundRequest) {
            this.outboundRequest = outboundRequest;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public void setRetryCount(int retryCount) {
            this.retryCount = retryCount;
        }

        public LocalDateTime getSubmittedAt() {
            return submittedAt;
        }

        public void setSubmittedAt(LocalDateTime submittedAt) {
            this.submittedAt = submittedAt;
        }

        public LocalDateTime getProcessingStartedAt() {
            return processingStartedAt;
        }

        public void setProcessingStartedAt(LocalDateTime processingStartedAt) {
            this.processingStartedAt = processingStartedAt;
        }

        public LocalDateTime getCompletedAt() {
            return completedAt;
        }

        public void setCompletedAt(LocalDateTime completedAt) {
            this.completedAt = completedAt;
        }

        public LocalDateTime getFailedAt() {
            return failedAt;
        }

        public void setFailedAt(LocalDateTime failedAt) {
            this.failedAt = failedAt;
        }
    }
}
