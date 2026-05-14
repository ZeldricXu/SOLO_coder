package com.schedulebook.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class AdjustmentDetectionQueueService {
    
    private static final Logger logger = LoggerFactory.getLogger(AdjustmentDetectionQueueService.class);
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Value("${schedulebook.reminder.queue-key:schedulebook:adjustment:queue}")
    private String queueKey;
    
    @Value("${schedulebook.reminder.worker-enabled:true}")
    private boolean workerEnabled;
    
    private ExecutorService workerExecutor;
    private volatile boolean running = false;
    
    @PostConstruct
    public void init() {
        if (workerEnabled) {
            startWorker();
        }
    }
    
    @PreDestroy
    public void shutdown() {
        stopWorker();
    }
    
    public void startWorker() {
        if (running) {
            return;
        }
        running = true;
        workerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "adjustment-detection-worker");
            thread.setDaemon(true);
            return thread;
        });
        
        workerExecutor.submit(this::runWorker);
        logger.info("调整检测Worker已启动");
    }
    
    public void stopWorker() {
        running = false;
        if (workerExecutor != null) {
            workerExecutor.shutdown();
            try {
                if (!workerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    workerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("调整检测Worker已停止");
    }
    
    private void runWorker() {
        while (running) {
            try {
                AdjustmentDetectionTask task = dequeueTask(5, TimeUnit.SECONDS);
                if (task != null) {
                    processTask(task);
                }
            } catch (Exception e) {
                logger.error("调整检测Worker处理任务时发生错误", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    
    public void enqueueTask(AdjustmentDetectionTask task) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().rightPush(queueKey, taskJson);
            logger.info("调整检测任务已入队，任务ID: {}, 类型: {}", task.getTaskId(), task.getTaskType());
        } catch (JsonProcessingException e) {
            logger.error("序列化调整检测任务失败", e);
            throw new RuntimeException("序列化调整检测任务失败", e);
        }
    }
    
    public AdjustmentDetectionTask dequeueTask(long timeout, TimeUnit unit) {
        Object result = redisTemplate.opsForList().leftPop(queueKey, timeout, unit);
        if (result == null) {
            return null;
        }
        
        try {
            String taskJson = (String) result;
            return objectMapper.readValue(taskJson, AdjustmentDetectionTask.class);
        } catch (JsonProcessingException e) {
            logger.error("反序列化调整检测任务失败", e);
            return null;
        }
    }
    
    public AdjustmentDetectionTask dequeueTask() {
        return dequeueTask(0, TimeUnit.SECONDS);
    }
    
    private void processTask(AdjustmentDetectionTask task) {
        logger.info("开始处理调整检测任务，任务ID: {}, 类型: {}", task.getTaskId(), task.getTaskType());
        
        try {
            switch (task.getTaskType()) {
                case "schedule_adjust":
                    processScheduleAdjustTask(task);
                    break;
                case "booking_adjust":
                    processBookingAdjustTask(task);
                    break;
                default:
                    logger.warn("未知的任务类型: {}", task.getTaskType());
            }
            logger.info("调整检测任务处理完成，任务ID: {}", task.getTaskId());
        } catch (Exception e) {
            logger.error("处理调整检测任务失败，任务ID: {}", task.getTaskId(), e);
            handleTaskFailure(task, e);
        }
    }
    
    private void processScheduleAdjustTask(AdjustmentDetectionTask task) {
        logger.debug("处理排班调整检测，排班ID: {}, 原时间: {}, 新时间: {}", 
                task.getScheduleId(), task.getOldTime(), task.getNewTime());
    }
    
    private void processBookingAdjustTask(AdjustmentDetectionTask task) {
        logger.debug("处理预约调整检测，预约ID: {}, 新日期: {}, 新时间: {}", 
                task.getBookingId(), task.getNewDate(), task.getNewTime());
    }
    
    private void handleTaskFailure(AdjustmentDetectionTask task, Exception e) {
        if (task.getRetryCount() < 3) {
            task.incrementRetryCount();
            task.setLastError(e.getMessage());
            enqueueTask(task);
            logger.info("任务已重新入队，重试次数: {}", task.getRetryCount());
        } else {
            logger.error("任务重试次数已达上限，任务ID: {}", task.getTaskId());
        }
    }
    
    public long getQueueSize() {
        Long size = redisTemplate.opsForList().size(queueKey);
        return size != null ? size : 0;
    }
    
    public void clearQueue() {
        redisTemplate.delete(queueKey);
        logger.info("调整检测队列已清空");
    }
    
    public List<AdjustmentDetectionTask> peekAllTasks() {
        List<AdjustmentDetectionTask> tasks = new ArrayList<>();
        List<Object> rawTasks = redisTemplate.opsForList().range(queueKey, 0, -1);
        
        if (rawTasks != null) {
            for (Object raw : rawTasks) {
                try {
                    String taskJson = (String) raw;
                    tasks.add(objectMapper.readValue(taskJson, AdjustmentDetectionTask.class));
                } catch (JsonProcessingException e) {
                    logger.error("反序列化任务失败", e);
                }
            }
        }
        
        return tasks;
    }
    
    public boolean isRunning() {
        return running;
    }
    
    public static class AdjustmentDetectionTask {
        private String taskId;
        private String taskType;
        private String scheduleId;
        private String bookingId;
        private LocalTime oldTime;
        private LocalTime newTime;
        private LocalDate newDate;
        private int retryCount;
        private String lastError;
        private long createdAt;
        
        public AdjustmentDetectionTask() {
            this.createdAt = System.currentTimeMillis();
        }
        
        public static AdjustmentDetectionTask createScheduleAdjustTask(
                String scheduleId, LocalTime oldTime, LocalTime newTime) {
            AdjustmentDetectionTask task = new AdjustmentDetectionTask();
            task.setTaskId("task_sched_" + System.currentTimeMillis() + "_" + scheduleId);
            task.setTaskType("schedule_adjust");
            task.setScheduleId(scheduleId);
            task.setOldTime(oldTime);
            task.setNewTime(newTime);
            return task;
        }
        
        public static AdjustmentDetectionTask createBookingAdjustTask(
                String bookingId, LocalDate newDate, LocalTime newTime) {
            AdjustmentDetectionTask task = new AdjustmentDetectionTask();
            task.setTaskId("task_booking_" + System.currentTimeMillis() + "_" + bookingId);
            task.setTaskType("booking_adjust");
            task.setBookingId(bookingId);
            task.setNewDate(newDate);
            task.setNewTime(newTime);
            return task;
        }
        
        public void incrementRetryCount() {
            this.retryCount++;
        }
        
        public String getTaskId() {
            return taskId;
        }
        
        public void setTaskId(String taskId) {
            this.taskId = taskId;
        }
        
        public String getTaskType() {
            return taskType;
        }
        
        public void setTaskType(String taskType) {
            this.taskType = taskType;
        }
        
        public String getScheduleId() {
            return scheduleId;
        }
        
        public void setScheduleId(String scheduleId) {
            this.scheduleId = scheduleId;
        }
        
        public String getBookingId() {
            return bookingId;
        }
        
        public void setBookingId(String bookingId) {
            this.bookingId = bookingId;
        }
        
        public LocalTime getOldTime() {
            return oldTime;
        }
        
        public void setOldTime(LocalTime oldTime) {
            this.oldTime = oldTime;
        }
        
        public LocalTime getNewTime() {
            return newTime;
        }
        
        public void setNewTime(LocalTime newTime) {
            this.newTime = newTime;
        }
        
        public LocalDate getNewDate() {
            return newDate;
        }
        
        public void setNewDate(LocalDate newDate) {
            this.newDate = newDate;
        }
        
        public int getRetryCount() {
            return retryCount;
        }
        
        public void setRetryCount(int retryCount) {
            this.retryCount = retryCount;
        }
        
        public String getLastError() {
            return lastError;
        }
        
        public void setLastError(String lastError) {
            this.lastError = lastError;
        }
        
        public long getCreatedAt() {
            return createdAt;
        }
        
        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }
    }
}
