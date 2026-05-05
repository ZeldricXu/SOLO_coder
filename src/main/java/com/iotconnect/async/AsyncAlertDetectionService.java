package com.iotconnect.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iotconnect.entity.Device;
import com.iotconnect.entity.DeviceData;
import com.iotconnect.queue.RedisPersistentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AsyncAlertDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncAlertDetectionService.class);
    private static final String QUEUE_NAME = "alert_detection";

    private final AlertDetectionProcessor alertDetectionProcessor;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${alert.async-detection.enabled:true}")
    private boolean asyncDetectionEnabled;

    @Value("${alert.async-detection.thread-pool-size:10}")
    private int threadPoolSize;

    @Value("${alert.async-detection.queue-capacity:10000}")
    private int queueCapacity;

    @Value("${alert.async-detection.use-redis:true}")
    private boolean useRedis;

    private ExecutorService executorService;
    private final ConcurrentHashMap<String, String> inProgressTasks = new ConcurrentHashMap<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private Thread consumerThread;

    public AsyncAlertDetectionService(AlertDetectionProcessor alertDetectionProcessor,
                                        RedisTemplate<String, Object> redisTemplate) {
        this.alertDetectionProcessor = alertDetectionProcessor;
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() {
        if (asyncDetectionEnabled) {
            logger.info("Initializing AsyncAlertDetectionService: threadPoolSize={}, queueCapacity={}, useRedis={}",
                    threadPoolSize, queueCapacity, useRedis);
            
            executorService = Executors.newFixedThreadPool(threadPoolSize);
            isRunning.set(true);
            
            if (useRedis) {
                startRedisConsumer();
                recoverIncompleteTasks();
            }
            
            logger.info("AsyncAlertDetectionService initialized successfully");
        } else {
            logger.info("AsyncAlertDetectionService is disabled");
        }
    }

    private void startRedisConsumer() {
        consumerThread = new Thread(() -> {
            logger.info("Redis consumer thread started for queue: {}", QUEUE_NAME);
            
            while (isRunning.get()) {
                try {
                    String taskJson = popFromRedisQueue();
                    
                    if (taskJson != null) {
                        SerializableAlertDetectionTask task = deserializeTask(taskJson);
                        if (task != null) {
                            submitTaskToExecutor(task);
                        }
                    } else {
                        Thread.sleep(100);
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.info("Redis consumer thread interrupted");
                    break;
                } catch (Exception e) {
                    logger.error("Error in Redis consumer thread: {}", e.getMessage(), e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            logger.info("Redis consumer thread stopped");
        }, "alert-detection-redis-consumer");
        
        consumerThread.setDaemon(true);
        consumerThread.start();
        logger.info("Redis consumer thread started");
    }

    private String popFromRedisQueue() {
        String queueKey = "iot:alert:queue:" + QUEUE_NAME;
        
        try {
            Object result = redisTemplate.opsForList().leftPop(queueKey, 5, java.util.concurrent.TimeUnit.SECONDS);
            if (result != null) {
                String itemId = result.toString();
                String dataKey = "iot:alert:data:" + itemId;
                
                Object taskData = redisTemplate.opsForValue().get(dataKey);
                if (taskData != null) {
                    String processingKey = "iot:alert:processing:" + itemId;
                    redisTemplate.opsForValue().set(processingKey, taskData);
                    redisTemplate.delete(dataKey);
                    
                    inProgressTasks.put(itemId, taskData.toString());
                    
                    return taskData.toString();
                } else {
                    logger.warn("Task data not found for itemId: {}", itemId);
                }
            }
        } catch (Exception e) {
            logger.debug("Redis queue pop error: {}", e.getMessage());
        }
        return null;
    }

    private void submitTaskToExecutor(SerializableAlertDetectionTask task) {
        Device device = task.toDevice();
        DeviceData deviceData = task.toDeviceData();
        
        logger.debug("Submitting task to executor: deviceId={}, metric={}", 
                device.getDeviceId(), deviceData.getDataType());
        
        executorService.submit(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                alertDetectionProcessor.processAlertDetection(device, deviceData);
                long duration = System.currentTimeMillis() - startTime;
                
                logger.debug("Alert detection completed: deviceId={}, metric={}, duration={}ms, queuedAt={}",
                        device.getDeviceId(), deviceData.getDataType(), duration, task.getQueuedAt());
                
            } catch (Exception e) {
                logger.error("Alert detection failed: deviceId={}, metric={}, error={}",
                        device.getDeviceId(), deviceData.getDataType(), e.getMessage(), e);
            }
        });
    }

    private void recoverIncompleteTasks() {
        logger.info("Recovering incomplete tasks from Redis...");
        
        try {
            String processingKeyPattern = "iot:alert:processing:*";
            var keys = redisTemplate.keys(processingKeyPattern);
            
            if (keys != null && !keys.isEmpty()) {
                logger.info("Found {} incomplete tasks in processing queue", keys.size());
                
                for (String processingKey : keys) {
                    try {
                        Object taskData = redisTemplate.opsForValue().get(processingKey);
                        if (taskData != null) {
                            String queueKey = "iot:alert:queue:" + QUEUE_NAME;
                            String itemId = processingKey.replace("iot:alert:processing:", "");
                            
                            String dataKey = "iot:alert:data:" + itemId;
                            redisTemplate.opsForValue().set(dataKey, taskData);
                            redisTemplate.opsForList().rightPush(queueKey, itemId);
                            redisTemplate.delete(processingKey);
                            
                            logger.info("Recovered task: itemId={}", itemId);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to recover task: key={}, error={}", processingKey, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to recover incomplete tasks: {}", e.getMessage());
        }
    }

    public CompletableFuture<AlertDetectionResult> submitDetection(Device device, DeviceData deviceData) {
        if (!asyncDetectionEnabled) {
            logger.debug("Async detection disabled, processing synchronously");
            return processSynchronously(device, deviceData);
        }

        if (useRedis) {
            return submitToRedisQueue(device, deviceData);
        } else {
            return submitToMemoryQueue(device, deviceData);
        }
    }

    private CompletableFuture<AlertDetectionResult> submitToRedisQueue(Device device, DeviceData deviceData) {
        try {
            SerializableAlertDetectionTask task = SerializableAlertDetectionTask.fromDeviceAndData(device, deviceData);
            String taskJson = objectMapper.writeValueAsString(task);
            
            String itemId = java.util.UUID.randomUUID().toString();
            String queueKey = "iot:alert:queue:" + QUEUE_NAME;
            String dataKey = "iot:alert:data:" + itemId;
            
            redisTemplate.opsForValue().set(dataKey, taskJson);
            redisTemplate.opsForList().rightPush(queueKey, itemId);
            
            logger.debug("Task submitted to Redis queue: deviceId={}, metric={}, itemId={}",
                    device.getDeviceId(), deviceData.getDataType(), itemId);
            
            CompletableFuture<AlertDetectionResult> future = new CompletableFuture<>();
            AlertDetectionResult result = new AlertDetectionResult(
                    device.getDeviceId(),
                    deviceData.getDataType(),
                    true,
                    0,
                    "Queued for processing"
            );
            future.complete(result);
            return future;
            
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize task: {}", e.getMessage());
            return processSynchronously(device, deviceData);
        }
    }

    private CompletableFuture<AlertDetectionResult> submitToMemoryQueue(Device device, DeviceData deviceData) {
        AlertDetectionTask task = new AlertDetectionTask(device, deviceData, alertDetectionProcessor);
        
        CompletableFuture<AlertDetectionResult> future = new CompletableFuture<>();
        
        executorService.submit(() -> {
            try {
                AlertDetectionResult result = task.call();
                future.complete(result);
                
                if (result.isSuccess()) {
                    logger.debug("Alert detection completed: deviceId={}, metric={}, duration={}ms",
                            result.getDeviceId(), result.getMetric(), result.getDurationMs());
                } else {
                    logger.warn("Alert detection failed: deviceId={}, metric={}, error={}",
                            result.getDeviceId(), result.getMetric(), result.getErrorMessage());
                }
                
            } catch (Exception e) {
                logger.error("Alert detection task execution error: {}", e.getMessage(), e);
                future.completeExceptionally(e);
            }
        });
        
        logger.debug("Alert detection task submitted to memory queue: deviceId={}, metric={}",
                device.getDeviceId(), deviceData.getDataType());

        return future;
    }

    private CompletableFuture<AlertDetectionResult> processSynchronously(Device device, DeviceData deviceData) {
        CompletableFuture<AlertDetectionResult> future = new CompletableFuture<>();
        
        try {
            long startTime = System.currentTimeMillis();
            alertDetectionProcessor.processAlertDetection(device, deviceData);
            long duration = System.currentTimeMillis() - startTime;
            
            AlertDetectionResult result = new AlertDetectionResult(
                    device.getDeviceId(),
                    deviceData.getDataType(),
                    true,
                    duration,
                    null
            );
            
            future.complete(result);
            logger.debug("Synchronous alert detection completed: deviceId={}, duration={}ms",
                    device.getDeviceId(), duration);
            
        } catch (Exception e) {
            logger.error("Synchronous alert detection failed: {}", e.getMessage(), e);
            
            AlertDetectionResult result = new AlertDetectionResult(
                    device.getDeviceId(),
                    deviceData.getDataType(),
                    false,
                    0,
                    e.getMessage()
            );
            future.complete(result);
        }
        
        return future;
    }

    @Async("notificationExecutor")
    public void processDetectionAsync(Device device, DeviceData deviceData) {
        if (!asyncDetectionEnabled) {
            alertDetectionProcessor.processAlertDetection(device, deviceData);
            return;
        }

        long startTime = System.currentTimeMillis();
        
        try {
            alertDetectionProcessor.processAlertDetection(device, deviceData);
            long duration = System.currentTimeMillis() - startTime;
            
            logger.debug("Async alert detection completed: deviceId={}, metric={}, duration={}ms",
                    device.getDeviceId(), deviceData.getDataType(), duration);
            
        } catch (Exception e) {
            logger.error("Async alert detection failed: deviceId={}, metric={}, error={}",
                    device.getDeviceId(), deviceData.getDataType(), e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down AsyncAlertDetectionService...");
        
        isRunning.set(false);
        
        if (consumerThread != null && consumerThread.isAlive()) {
            consumerThread.interrupt();
            try {
                consumerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        logger.info("AsyncAlertDetectionService shut down. In-progress tasks: {}", inProgressTasks.size());
    }

    public long getQueueSize() {
        if (useRedis) {
            String queueKey = "iot:alert:queue:" + QUEUE_NAME;
            Long size = redisTemplate.opsForList().size(queueKey);
            return size != null ? size : 0;
        }
        return 0;
    }

    public long getProcessingCount() {
        if (useRedis) {
            var keys = redisTemplate.keys("iot:alert:processing:*");
            return keys != null ? keys.size() : 0;
        }
        return inProgressTasks.size();
    }

    public boolean isAsyncDetectionEnabled() {
        return asyncDetectionEnabled;
    }

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public boolean isUseRedis() {
        return useRedis;
    }

    private SerializableAlertDetectionTask deserializeTask(String json) {
        try {
            return objectMapper.readValue(json, SerializableAlertDetectionTask.class);
        } catch (Exception e) {
            logger.error("Failed to deserialize task: {}", e.getMessage());
            return null;
        }
    }

    @Scheduled(fixedRate = 60000)
    public void reportQueueStats() {
        if (!asyncDetectionEnabled) {
            return;
        }
        
        long queueSize = getQueueSize();
        long processingCount = getProcessingCount();
        
        if (queueSize > 0 || processingCount > 0) {
            logger.info("Alert detection queue stats: pending={}, processing={}", queueSize, processingCount);
        }
    }
}
