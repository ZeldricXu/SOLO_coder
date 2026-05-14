package com.homeservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeservice.config.RedisConfig;
import com.homeservice.entity.Booking;
import com.homeservice.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RedisSettlementQueueService {

    private static final Logger logger = LoggerFactory.getLogger(RedisSettlementQueueService.class);

    @Autowired(required = false)
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RedisConfig redisConfig;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private ObjectMapper objectMapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService workerExecutor;
    private final Map<String, SettlementTaskInfo> inMemoryFallback = new ConcurrentHashMap<>();

    public static class SettlementTaskInfo {
        private String settlementId;
        private String bookingId;
        private String staffId;
        private double serviceAmount;
        private double platformFee;
        private double staffAmount;
        private int retryCount;
        private String status;
        private Instant createdAt;
        private Instant lastAttemptAt;
        private String errorMessage;

        public SettlementTaskInfo() {}

        public SettlementTaskInfo(String settlementId, String bookingId, String staffId,
                                  double serviceAmount, double platformFee, double staffAmount) {
            this.settlementId = settlementId;
            this.bookingId = bookingId;
            this.staffId = staffId;
            this.serviceAmount = serviceAmount;
            this.platformFee = platformFee;
            this.staffAmount = staffAmount;
            this.retryCount = 0;
            this.status = "PENDING";
            this.createdAt = Instant.now();
        }

        public String getSettlementId() { return settlementId; }
        public void setSettlementId(String settlementId) { this.settlementId = settlementId; }
        public String getBookingId() { return bookingId; }
        public void setBookingId(String bookingId) { this.bookingId = bookingId; }
        public String getStaffId() { return staffId; }
        public void setStaffId(String staffId) { this.staffId = staffId; }
        public double getServiceAmount() { return serviceAmount; }
        public void setServiceAmount(double serviceAmount) { this.serviceAmount = serviceAmount; }
        public double getPlatformFee() { return platformFee; }
        public void setPlatformFee(double platformFee) { this.platformFee = platformFee; }
        public double getStaffAmount() { return staffAmount; }
        public void setStaffAmount(double staffAmount) { this.staffAmount = staffAmount; }
        public int getRetryCount() { return retryCount; }
        public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
        public void incrementRetryCount() { this.retryCount++; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getLastAttemptAt() { return lastAttemptAt; }
        public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public boolean canRetry(int maxRetries) { return retryCount < maxRetries; }
    }

    @PostConstruct
    public void init() {
        if (redisConfig.isEnabled() && redisTemplate != null) {
            logger.info("Redis settlement queue enabled, starting worker");
            running.set(true);
            workerExecutor = Executors.newSingleThreadExecutor();
            startWorker();
        } else {
            logger.info("Redis not enabled, using in-memory fallback for settlement queue");
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (workerExecutor != null) {
            workerExecutor.shutdown();
            try {
                if (!workerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    workerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerExecutor.shutdownNow();
            }
        }
        logger.info("Redis settlement queue service shutdown complete");
    }

    private void startWorker() {
        workerExecutor.submit(() -> {
            while (running.get()) {
                try {
                    processQueue();
                    Thread.sleep(redisConfig.getQueue().getPollIntervalMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.info("Settlement worker interrupted");
                    break;
                } catch (Exception e) {
                    logger.error("Error in settlement worker: {}", e.getMessage(), e);
                }
            }
        });
    }

    public boolean submitSettlementTask(String settlementId, Booking booking) {
        double platformFeeRate = 0.10;
        double serviceAmount = booking.getBookingAmount();
        double platformFee = serviceAmount * platformFeeRate;
        double staffAmount = serviceAmount - platformFee;

        SettlementTaskInfo task = new SettlementTaskInfo(
            settlementId,
            booking.getBookingId(),
            booking.getStaffId(),
            serviceAmount,
            platformFee,
            staffAmount
        );

        if (redisConfig.isEnabled() && redisTemplate != null) {
            return submitToRedis(task);
        } else {
            return submitToInMemory(task);
        }
    }

    private boolean submitToRedis(SettlementTaskInfo task) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().rightPush(
                redisConfig.getQueue().getSettlementQueueKey(),
                taskJson
            );
            logger.info("Settlement task {} submitted to Redis queue for booking {}", 
                task.getSettlementId(), task.getBookingId());
            return true;
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize settlement task: {}", e.getMessage());
            return false;
        }
    }

    private boolean submitToInMemory(SettlementTaskInfo task) {
        inMemoryFallback.put(task.getSettlementId(), task);
        logger.info("Settlement task {} stored in memory for booking {}", 
            task.getSettlementId(), task.getBookingId());
        processInMemoryTask(task);
        return true;
    }

    private void processQueue() {
        String taskJson = redisTemplate.opsForList().leftPop(
            redisConfig.getQueue().getSettlementQueueKey(),
            0,
            TimeUnit.SECONDS
        );

        if (taskJson == null) {
            return;
        }

        try {
            SettlementTaskInfo task = objectMapper.readValue(taskJson, SettlementTaskInfo.class);
            processTask(task);
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize settlement task: {}", e.getMessage());
            moveToFailed(taskJson, "Deserialization failed: " + e.getMessage());
        }
    }

    private void processTask(SettlementTaskInfo task) {
        logger.info("Processing settlement task {} for booking {}", 
            task.getSettlementId(), task.getBookingId());

        try {
            Optional<Booking> bookingOpt = bookingRepository.findByBookingId(task.getBookingId());
            if (bookingOpt.isEmpty()) {
                throw new RuntimeException("Booking not found: " + task.getBookingId());
            }

            Booking booking = bookingOpt.get();
            boolean success = executeSettlement(task, booking);

            if (success) {
                task.setStatus("COMPLETED");
                logger.info("Settlement task {} completed successfully", task.getSettlementId());
            } else {
                handleTaskFailure(task, "Settlement execution failed");
            }
        } catch (Exception e) {
            handleTaskFailure(task, e.getMessage());
        }
    }

    private boolean executeSettlement(SettlementTaskInfo task, Booking booking) {
        task.setLastAttemptAt(Instant.now());
        task.incrementRetryCount();
        
        try {
            settlementService.processSettlement(task.getBookingId());
            return true;
        } catch (Exception e) {
            task.setErrorMessage(e.getMessage());
            logger.warn("Settlement attempt {} failed for task {}: {}", 
                task.getRetryCount(), task.getSettlementId(), e.getMessage());
            return false;
        }
    }

    private void handleTaskFailure(SettlementTaskInfo task, String errorMessage) {
        task.setErrorMessage(errorMessage);

        if (task.canRetry(redisConfig.getQueue().getMaxRetries())) {
            logger.info("Settlement task {} will be retried (attempt {}/{})",
                task.getSettlementId(), task.getRetryCount(), redisConfig.getQueue().getMaxRetries());
            
            try {
                String taskJson = objectMapper.writeValueAsString(task);
                redisTemplate.opsForList().rightPush(
                    redisConfig.getQueue().getSettlementQueueKey(),
                    taskJson
                );
            } catch (JsonProcessingException e) {
                logger.error("Failed to reserialize task for retry: {}", e.getMessage());
                moveToFailed(task, errorMessage);
            }
        } else {
            moveToFailed(task, errorMessage);
        }
    }

    private void moveToFailed(SettlementTaskInfo task, String errorMessage) {
        task.setStatus("FAILED");
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForHash().put(
                redisConfig.getQueue().getFailedKey(),
                task.getSettlementId(),
                taskJson
            );
            logger.error("Settlement task {} moved to failed queue after {} attempts: {}",
                task.getSettlementId(), task.getRetryCount(), errorMessage);
        } catch (JsonProcessingException e) {
            logger.error("Failed to store failed task: {}", e.getMessage());
        }
    }

    private void moveToFailed(String taskJson, String errorMessage) {
        String failedKey = "failed:" + System.currentTimeMillis();
        redisTemplate.opsForHash().put(
            redisConfig.getQueue().getFailedKey(),
            failedKey,
            taskJson
        );
        logger.error("Task moved to failed queue: {}", errorMessage);
    }

    private void processInMemoryTask(SettlementTaskInfo task) {
        logger.info("Processing in-memory settlement task {} for booking {}",
            task.getSettlementId(), task.getBookingId());
        
        for (int attempt = 0; attempt < redisConfig.getQueue().getMaxRetries(); attempt++) {
            try {
                Optional<Booking> bookingOpt = bookingRepository.findByBookingId(task.getBookingId());
                if (bookingOpt.isPresent()) {
                    settlementService.processSettlement(task.getBookingId());
                    task.setStatus("COMPLETED");
                    logger.info("In-memory settlement task {} completed", task.getSettlementId());
                    return;
                }
            } catch (Exception e) {
                task.incrementRetryCount();
                task.setErrorMessage(e.getMessage());
                logger.warn("In-memory settlement attempt {} failed: {}", 
                    task.getRetryCount(), e.getMessage());
                
                if (task.getRetryCount() >= redisConfig.getQueue().getMaxRetries()) {
                    task.setStatus("FAILED");
                    logger.error("In-memory settlement task {} failed after {} attempts",
                        task.getSettlementId(), task.getRetryCount());
                    return;
                }
                
                try {
                    Thread.sleep(redisConfig.getQueue().getRetryDelayMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public SettlementTaskInfo getTaskInfo(String settlementId) {
        if (redisConfig.isEnabled() && redisTemplate != null) {
            Object taskObj = redisTemplate.opsForHash().get(
                redisConfig.getQueue().getFailedKey(),
                settlementId
            );
            if (taskObj != null) {
                try {
                    return objectMapper.readValue((String) taskObj, SettlementTaskInfo.class);
                } catch (JsonProcessingException e) {
                    logger.error("Failed to read task info: {}", e.getMessage());
                }
            }
        }
        return inMemoryFallback.get(settlementId);
    }

    public int getPendingTaskCount() {
        if (redisConfig.isEnabled() && redisTemplate != null) {
            Long size = redisTemplate.opsForList().size(redisConfig.getQueue().getSettlementQueueKey());
            return size != null ? size.intValue() : 0;
        }
        return (int) inMemoryFallback.values().stream()
            .filter(t -> "PENDING".equals(t.getStatus()))
            .count();
    }

    public int getFailedTaskCount() {
        if (redisConfig.isEnabled() && redisTemplate != null) {
            Long size = redisTemplate.opsForHash().size(redisConfig.getQueue().getFailedKey());
            return size != null ? size.intValue() : 0;
        }
        return (int) inMemoryFallback.values().stream()
            .filter(t -> "FAILED".equals(t.getStatus()))
            .count();
    }

    @Scheduled(fixedRate = 60000)
    public void logQueueStats() {
        if (redisConfig.isEnabled()) {
            logger.info("Settlement queue stats - pending: {}, failed: {}",
                getPendingTaskCount(), getFailedTaskCount());
        }
    }
}
