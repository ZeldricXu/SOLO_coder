package com.hotelbooking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelbooking.dto.CheckInRequest;
import com.hotelbooking.model.CheckIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class CheckInQueueService {
    private static final Logger logger = LoggerFactory.getLogger(CheckInQueueService.class);

    @Value("${hotelbooking.checkin.queue.name:checkin_queue}")
    private String queueName;

    @Value("${hotelbooking.checkin.worker.pool-size:5}")
    private int workerPoolSize;

    @Value("${hotelbooking.checkin.worker.max-retries:3}")
    private int maxRetries;

    @Value("${hotelbooking.checkin.worker.retry-delay:1000}")
    private long retryDelay;

    private final RedisTemplate<String, Object> redisTemplate;
    private final CheckInService checkInService;
    private final ObjectMapper objectMapper;
    private ExecutorService workerPool;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public CheckInQueueService(RedisTemplate<String, Object> redisTemplate,
                                CheckInService checkInService,
                                ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.checkInService = checkInService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void startWorkers() {
        this.workerPool = Executors.newFixedThreadPool(workerPoolSize);
        logger.info("启动入住登记队列处理器，队列名: {}, 线程池大小: {}", queueName, workerPoolSize);
        for (int i = 0; i < workerPoolSize; i++) {
            workerPool.submit(this::workerLoop);
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("正在关闭入住登记队列处理器...");
        running.set(false);
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(30, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("入住登记队列处理器已关闭");
    }

    public String submitCheckInTask(CheckInRequest request) {
        String taskId = generateTaskId();
        CheckInTask task = new CheckInTask(taskId, request, 0);
        
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().rightPush(queueName, taskJson);
            logger.info("入住登记任务已提交到Redis队列: taskId={}, bookingId={}", taskId, request.getBookingId());
        } catch (JsonProcessingException e) {
            logger.error("序列化入住登记任务失败: {}", e.getMessage(), e);
            throw new RuntimeException("提交入住登记任务失败", e);
        }
        
        return taskId;
    }

    private void workerLoop() {
        logger.info("入住登记Worker已启动: {}", Thread.currentThread().getName());
        
        while (running.get()) {
            try {
                Object taskObj = redisTemplate.opsForList().leftPop(queueName, 1, TimeUnit.SECONDS);
                
                if (taskObj == null) {
                    continue;
                }
                
                String taskJson = taskObj.toString();
                CheckInTask task = objectMapper.readValue(taskJson, CheckInTask.class);
                
                logger.info("Worker处理入住登记任务: taskId={}, 尝试次数={}", task.getTaskId(), task.getRetryCount());
                
                processCheckInTask(task);
                
            } catch (JsonProcessingException e) {
                logger.error("反序列化入住登记任务失败: {}", e.getMessage(), e);
            } catch (Exception e) {
                if (running.get()) {
                    logger.error("Worker处理任务时发生错误: {}", e.getMessage(), e);
                }
            }
        }
        
        logger.info("入住登记Worker已退出: {}", Thread.currentThread().getName());
    }

    private void processCheckInTask(CheckInTask task) {
        try {
            CheckIn checkIn = checkInService.checkIn(task.getRequest());
            logger.info("入住登记任务处理成功: taskId={}, checkinId={}", task.getTaskId(), checkIn.getCheckinId());
            recordTaskResult(task.getTaskId(), checkIn, true, null);
            
        } catch (RuntimeException e) {
            logger.warn("入住登记任务处理失败: taskId={}, 错误={}, 尝试次数={}", 
                    task.getTaskId(), e.getMessage(), task.getRetryCount());
            
            if (task.getRetryCount() < maxRetries - 1) {
                task.incrementRetryCount();
                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                
                try {
                    String taskJson = objectMapper.writeValueAsString(task);
                    redisTemplate.opsForList().rightPush(queueName, taskJson);
                    logger.info("入住登记任务已重新入队: taskId={}, 新尝试次数={}", task.getTaskId(), task.getRetryCount());
                } catch (JsonProcessingException je) {
                    logger.error("重新序列化任务失败: {}", je.getMessage());
                    recordTaskResult(task.getTaskId(), null, false, e.getMessage());
                }
            } else {
                logger.error("入住登记任务已达到最大重试次数: taskId={}", task.getTaskId());
                recordTaskResult(task.getTaskId(), null, false, e.getMessage());
            }
        }
    }

    private void recordTaskResult(String taskId, CheckIn checkIn, boolean success, String errorMessage) {
        String resultKey = "checkin:result:" + taskId;
        TaskResult result = new TaskResult(taskId, checkIn, success, errorMessage);
        try {
            String resultJson = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(resultKey, resultJson, 24, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            logger.error("记录任务结果失败: {}", e.getMessage());
        }
    }

    public TaskResult getTaskResult(String taskId) {
        String resultKey = "checkin:result:" + taskId;
        Object resultObj = redisTemplate.opsForValue().get(resultKey);
        if (resultObj == null) {
            return null;
        }
        try {
            return objectMapper.readValue(resultObj.toString(), TaskResult.class);
        } catch (JsonProcessingException e) {
            logger.error("解析任务结果失败: {}", e.getMessage());
            return null;
        }
    }

    public long getQueueSize() {
        Long size = redisTemplate.opsForList().size(queueName);
        return size != null ? size : 0;
    }

    private String generateTaskId() {
        return "task_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
    }

    public static class CheckInTask {
        private String taskId;
        private CheckInRequest request;
        private int retryCount;

        public CheckInTask() {}

        public CheckInTask(String taskId, CheckInRequest request, int retryCount) {
            this.taskId = taskId;
            this.request = request;
            this.retryCount = retryCount;
        }

        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public CheckInRequest getRequest() { return request; }
        public void setRequest(CheckInRequest request) { this.request = request; }
        public int getRetryCount() { return retryCount; }
        public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

        public void incrementRetryCount() {
            this.retryCount++;
        }
    }

    public static class TaskResult {
        private String taskId;
        private CheckIn checkIn;
        private boolean success;
        private String errorMessage;
        private long timestamp;

        public TaskResult() {
            this.timestamp = System.currentTimeMillis();
        }

        public TaskResult(String taskId, CheckIn checkIn, boolean success, String errorMessage) {
            this.taskId = taskId;
            this.checkIn = checkIn;
            this.success = success;
            this.errorMessage = errorMessage;
            this.timestamp = System.currentTimeMillis();
        }

        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public CheckIn getCheckIn() { return checkIn; }
        public void setCheckIn(CheckIn checkIn) { this.checkIn = checkIn; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }
}
