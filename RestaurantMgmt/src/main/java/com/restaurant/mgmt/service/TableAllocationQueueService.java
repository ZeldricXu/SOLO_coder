package com.restaurant.mgmt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.restaurant.mgmt.exception.BusinessException;
import com.restaurant.mgmt.model.TableAllocationTask;
import com.restaurant.mgmt.util.IdGenerator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class TableAllocationQueueService {

    private static final Logger logger = LoggerFactory.getLogger(TableAllocationQueueService.class);

    private static final String QUEUE_KEY = "restaurant:table:allocation:queue";
    private static final String PROCESSING_KEY = "restaurant:table:allocation:processing";
    private static final String DEAD_LETTER_KEY = "restaurant:table:allocation:dlq";
    private static final String TASK_PREFIX = "restaurant:table:task:";

    @Autowired(required = false)
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private TableService tableService;

    @Autowired
    private HistoryService historyService;

    @Value("${restaurant.table.allocation.worker-count:3}")
    private int workerCount;

    @Value("${restaurant.table.allocation.max-retry:3}")
    private int maxRetry;

    @Value("${restaurant.table.allocation.enabled:true}")
    private boolean queueEnabled;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExecutorService workerExecutor;
    private volatile boolean running = false;

    public TableAllocationQueueService() {
        objectMapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() {
        if (queueEnabled && redisTemplate != null) {
            logger.info("座位分配持久化队列已启用，Redis连接正常");
            startWorkers();
        } else {
            logger.info("座位分配持久化队列未启用或Redis不可用，使用内存模式");
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
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
        logger.info("座位分配队列服务已关闭");
    }

    private void startWorkers() {
        running = true;
        workerExecutor = Executors.newFixedThreadPool(workerCount);
        
        for (int i = 0; i < workerCount; i++) {
            final int workerId = i;
            workerExecutor.submit(() -> {
                logger.info("座位分配Worker {} 已启动", workerId);
                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
                        TableAllocationTask task = dequeueTask();
                        if (task != null) {
                            processTask(task);
                        } else {
                            Thread.sleep(1000);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        logger.error("Worker {} 处理任务异常: {}", workerId, e.getMessage());
                    }
                }
                logger.info("座位分配Worker {} 已停止", workerId);
            });
        }
    }

    public String enqueueTask(TableAllocationTask task) {
        String taskId = IdGenerator.generateTableTaskId();
        task.setTaskId(taskId);
        
        if (queueEnabled && redisTemplate != null) {
            try {
                String taskJson = objectMapper.writeValueAsString(task);
                redisTemplate.opsForValue().set(TASK_PREFIX + taskId, taskJson);
                redisTemplate.opsForList().rightPush(QUEUE_KEY, taskId);
                logger.info("座位分配任务已入队: {}, 类型: {}", taskId, task.getTaskType());
                
                historyService.recordHistory(
                    "table_allocation",
                    taskId,
                    "任务入队",
                    "任务类型: " + task.getTaskType() + ", 桌号: " + task.getTableNumber(),
                    "system",
                    "enqueue",
                    "success"
                );
                
                return taskId;
            } catch (JsonProcessingException e) {
                logger.error("序列化任务失败: {}", e.getMessage());
                throw new BusinessException("任务序列化失败");
            }
        } else {
            logger.warn("Redis不可用，使用同步处理模式");
            processTask(task);
            return taskId;
        }
    }

    private TableAllocationTask dequeueTask() {
        if (redisTemplate == null) {
            return null;
        }
        
        try {
            String taskId = redisTemplate.opsForList().leftPop(QUEUE_KEY, 5, TimeUnit.SECONDS);
            if (taskId == null) {
                return null;
            }
            
            String taskJson = redisTemplate.opsForValue().get(TASK_PREFIX + taskId);
            if (taskJson == null) {
                logger.warn("任务数据不存在: {}", taskId);
                return null;
            }
            
            TableAllocationTask task = objectMapper.readValue(taskJson, TableAllocationTask.class);
            task.setStatus("processing");
            redisTemplate.opsForValue().set(TASK_PREFIX + taskId, objectMapper.writeValueAsString(task));
            redisTemplate.opsForSet().add(PROCESSING_KEY, taskId);
            
            return task;
        } catch (Exception e) {
            logger.error("出队任务失败: {}", e.getMessage());
            return null;
        }
    }

    private void processTask(TableAllocationTask task) {
        logger.info("开始处理座位分配任务: {}, 类型: {}", task.getTaskId(), task.getTaskType());
        
        try {
            switch (task.getTaskType()) {
                case "reserve":
                    handleReserveTask(task);
                    break;
                case "allocate":
                    handleAllocateTask(task);
                    break;
                case "occupy":
                    handleOccupyTask(task);
                    break;
                case "release":
                    handleReleaseTask(task);
                    break;
                case "cancel":
                    handleCancelTask(task);
                    break;
                default:
                    logger.warn("未知任务类型: {}", task.getTaskType());
            }
            
            completeTask(task);
            logger.info("座位分配任务处理完成: {}", task.getTaskId());
            
        } catch (Exception e) {
            logger.error("处理座位分配任务失败: {}, 错误: {}", task.getTaskId(), e.getMessage());
            handleTaskFailure(task, e.getMessage());
        }
    }

    private void handleReserveTask(TableAllocationTask task) {
        if (task.getTableNumber() != null && task.getReserveTime() != null) {
            tableService.reserveTable(
                task.getTableNumber(),
                task.getReserveTime(),
                task.getReservedBy() != null ? task.getReservedBy() : "system"
            );
        }
    }

    private void handleAllocateTask(TableAllocationTask task) {
        if (task.getGuestCount() > 0) {
            var allocated = tableService.allocateTable(task.getGuestCount());
            if (allocated != null) {
                task.setTableId(allocated.getTableId());
                task.setTableNumber(allocated.getTableNumber());
            }
        }
    }

    private void handleOccupyTask(TableAllocationTask task) {
        if (task.getTableId() != null) {
            tableService.occupyTable(task.getTableId());
        }
    }

    private void handleReleaseTask(TableAllocationTask task) {
        if (task.getTableId() != null) {
            tableService.releaseTable(task.getTableId());
        }
    }

    private void handleCancelTask(TableAllocationTask task) {
        if (task.getTableId() != null) {
            tableService.cancelReservation(task.getTableId(), 
                task.getErrorMessage() != null ? task.getErrorMessage() : "系统取消");
        }
    }

    private void completeTask(TableAllocationTask task) {
        task.setStatus("completed");
        task.setProcessedAt(LocalDateTime.now());
        
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForSet().remove(PROCESSING_KEY, task.getTaskId());
                redisTemplate.opsForValue().set(TASK_PREFIX + task.getTaskId(), 
                    objectMapper.writeValueAsString(task), 1, TimeUnit.HOURS);
            } catch (JsonProcessingException e) {
                logger.error("更新任务状态失败: {}", e.getMessage());
            }
        }
        
        historyService.recordHistory(
            "table_allocation",
            task.getTaskId(),
            "任务完成",
            "任务类型: " + task.getTaskType(),
            "system",
            "complete",
            "success"
        );
    }

    private void handleTaskFailure(TableAllocationTask task, String errorMessage) {
        task.setStatus("failed");
        task.setErrorMessage(errorMessage);
        task.incrementRetryCount();
        
        if (task.getRetryCount() < maxRetry) {
            logger.info("任务重试 {}/{}: {}", task.getRetryCount(), maxRetry, task.getTaskId());
            task.setStatus("pending");
            
            if (redisTemplate != null) {
                try {
                    redisTemplate.opsForSet().remove(PROCESSING_KEY, task.getTaskId());
                    redisTemplate.opsForValue().set(TASK_PREFIX + task.getTaskId(),
                        objectMapper.writeValueAsString(task));
                    redisTemplate.opsForList().rightPush(QUEUE_KEY, task.getTaskId());
                } catch (JsonProcessingException e) {
                    logger.error("重入队失败: {}", e.getMessage());
                }
            }
        } else {
            logger.error("任务重试次数用尽，移入死信队列: {}", task.getTaskId());
            sendToDeadLetterQueue(task);
        }
    }

    private void sendToDeadLetterQueue(TableAllocationTask task) {
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForSet().remove(PROCESSING_KEY, task.getTaskId());
                String taskJson = objectMapper.writeValueAsString(task);
                redisTemplate.opsForList().rightPush(DEAD_LETTER_KEY, taskJson);
                redisTemplate.delete(TASK_PREFIX + task.getTaskId());
            } catch (JsonProcessingException e) {
                logger.error("移入死信队列失败: {}", e.getMessage());
            }
        }
        
        historyService.recordHistory(
            "table_allocation",
            task.getTaskId(),
            "任务失败",
            "错误: " + task.getErrorMessage(),
            "system",
            "fail",
            "failed"
        );
    }

    @Scheduled(fixedDelay = 60000)
    public void recoverStuckTasks() {
        if (redisTemplate == null || !running) {
            return;
        }
        
        Set<String> processingTasks = redisTemplate.opsForSet().members(PROCESSING_KEY);
        if (processingTasks == null) {
            return;
        }
        
        for (String taskId : processingTasks) {
            try {
                String taskJson = redisTemplate.opsForValue().get(TASK_PREFIX + taskId);
                if (taskJson != null) {
                    TableAllocationTask task = objectMapper.readValue(taskJson, TableAllocationTask.class);
                    if (task.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(5))) {
                        logger.warn("检测到卡住的任务，重新入队: {}", taskId);
                        task.setStatus("pending");
                        redisTemplate.opsForValue().set(TASK_PREFIX + taskId, 
                            objectMapper.writeValueAsString(task));
                        redisTemplate.opsForSet().remove(PROCESSING_KEY, taskId);
                        redisTemplate.opsForList().rightPush(QUEUE_KEY, taskId);
                    }
                }
            } catch (Exception e) {
                logger.error("恢复卡住任务失败: {}", e.getMessage());
            }
        }
    }

    public long getQueueSize() {
        if (redisTemplate == null) {
            return 0;
        }
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }

    public long getProcessingCount() {
        if (redisTemplate == null) {
            return 0;
        }
        Long size = redisTemplate.opsForSet().size(PROCESSING_KEY);
        return size != null ? size : 0;
    }

    public long getDeadLetterCount() {
        if (redisTemplate == null) {
            return 0;
        }
        Long size = redisTemplate.opsForList().size(DEAD_LETTER_KEY);
        return size != null ? size : 0;
    }

    public List<TableAllocationTask> getDeadLetterTasks() {
        List<TableAllocationTask> tasks = new ArrayList<>();
        if (redisTemplate == null) {
            return tasks;
        }
        
        List<String> taskJsons = redisTemplate.opsForList().range(DEAD_LETTER_KEY, 0, -1);
        if (taskJsons != null) {
            for (String json : taskJsons) {
                try {
                    tasks.add(objectMapper.readValue(json, TableAllocationTask.class));
                } catch (JsonProcessingException e) {
                    logger.error("解析死信任务失败: {}", e.getMessage());
                }
            }
        }
        return tasks;
    }

    public boolean isQueueEnabled() {
        return queueEnabled && redisTemplate != null;
    }
}
