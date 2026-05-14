package com.travelbooking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.travelbooking.config.SettlementConfig;
import com.travelbooking.dto.SettlementTaskDTO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisSettlementQueueService {

    private final StringRedisTemplate redisTemplate;
    private final SettlementConfig settlementConfig;
    private final SettlementProcessor settlementProcessor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExecutorService workerExecutor;
    private volatile boolean running = false;

    @PostConstruct
    public void init() {
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        if (settlementConfig.isPersistenceEnabled()) {
            startWorkers();
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (workerExecutor != null) {
            workerExecutor.shutdown();
            try {
                if (!workerExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    workerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("Redis结算队列服务已关闭");
    }

    private void startWorkers() {
        running = true;
        workerExecutor = Executors.newFixedThreadPool(settlementConfig.getWorkerPoolSize());

        for (int i = 0; i < settlementConfig.getWorkerPoolSize(); i++) {
            workerExecutor.submit(this::runWorker);
        }

        log.info("Redis结算队列Worker已启动，线程数: {}", settlementConfig.getWorkerPoolSize());

        recoverPendingTasks();
    }

    private void runWorker() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                String taskJson = redisTemplate.opsForList()
                        .rightPop(settlementConfig.getRedisQueueName(), 5, TimeUnit.SECONDS);

                if (taskJson != null) {
                    processTask(taskJson);
                }
            } catch (Exception e) {
                if (running) {
                    log.error("Worker处理任务异常", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void processTask(String taskJson) {
        SettlementTaskDTO task;
        try {
            task = objectMapper.readValue(taskJson, SettlementTaskDTO.class);
        } catch (JsonProcessingException e) {
            log.error("解析结算任务JSON失败: {}", taskJson, e);
            return;
        }

        log.info("开始处理结算任务 - 任务ID: {}, 预订ID: {}, 重试次数: {}", 
                task.getTaskId(), task.getBookingId(), task.getRetryCount());

        try {
            boolean success = settlementProcessor.processTask(task);
            if (success) {
                log.info("结算任务成功 - 任务ID: {}, 预订ID: {}", task.getTaskId(), task.getBookingId());
            } else {
                handleTaskFailure(task, "处理失败");
            }
        } catch (Exception e) {
            log.error("结算任务执行异常 - 任务ID: {}", task.getTaskId(), e);
            handleTaskFailure(task, e.getMessage());
        }
    }

    private void handleTaskFailure(SettlementTaskDTO task, String error) {
        task.incrementRetry().setFailed(error);

        if (task.canRetry()) {
            log.warn("结算任务重试 - 任务ID: {}, 当前重试: {}/{}, 错误: {}", 
                    task.getTaskId(), task.getRetryCount(), task.getMaxRetries(), error);
            
            task.setNextRetryTime(Instant.now().plusMillis(
                    settlementConfig.getRetryDelayMs() * task.getRetryCount()
            ));
            
            try {
                String retryTaskJson = objectMapper.writeValueAsString(task);
                redisTemplate.opsForList()
                        .leftPush(settlementConfig.getRetryQueueName(), retryTaskJson);
            } catch (JsonProcessingException e) {
                log.error("序列化重试任务失败", e);
                moveToDeadLetter(task);
            }
        } else {
            log.error("结算任务最终失败 - 任务ID: {}, 预订ID: {}, 最终错误: {}", 
                    task.getTaskId(), task.getBookingId(), error);
            moveToDeadLetter(task);
        }
    }

    private void moveToDeadLetter(SettlementTaskDTO task) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList()
                    .leftPush(settlementConfig.getDeadLetterQueueName(), taskJson);
            log.warn("任务已移至死信队列 - 任务ID: {}", task.getTaskId());
        } catch (JsonProcessingException e) {
            log.error("序列化死信任务失败", e);
        }
    }

    public boolean enqueueTask(SettlementTaskDTO task) {
        try {
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().leftPush(settlementConfig.getRedisQueueName(), taskJson);
            log.info("结算任务已入队 - 任务ID: {}, 预订ID: {}", task.getTaskId(), task.getBookingId());
            return true;
        } catch (JsonProcessingException e) {
            log.error("序列化结算任务失败", e);
            return false;
        }
    }

    public void recoverPendingTasks() {
        String retryQueue = settlementConfig.getRetryQueueName();
        Long retryCount = redisTemplate.opsForList().size(retryQueue);
        
        if (retryCount != null && retryCount > 0) {
            log.info("发现{}个待重试的结算任务，开始恢复处理", retryCount);
            
            for (int i = 0; i < retryCount; i++) {
                String taskJson = redisTemplate.opsForList().rightPop(retryQueue);
                if (taskJson != null) {
                    try {
                        SettlementTaskDTO task = objectMapper.readValue(taskJson, SettlementTaskDTO.class);
                        if (task.getNextRetryTime() == null || 
                            Instant.now().isAfter(task.getNextRetryTime())) {
                            redisTemplate.opsForList().leftPush(settlementConfig.getRedisQueueName(), taskJson);
                            log.debug("恢复重试任务 - 任务ID: {}", task.getTaskId());
                        } else {
                            redisTemplate.opsForList().leftPush(retryQueue, taskJson);
                        }
                    } catch (JsonProcessingException e) {
                        log.error("解析重试任务失败", e);
                    }
                }
            }
        }
    }

    public Map<String, Long> getQueueStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("mainQueue", redisTemplate.opsForList().size(settlementConfig.getRedisQueueName()));
        stats.put("retryQueue", redisTemplate.opsForList().size(settlementConfig.getRetryQueueName()));
        stats.put("deadLetterQueue", redisTemplate.opsForList().size(settlementConfig.getDeadLetterQueueName()));
        return stats;
    }

    public List<SettlementTaskDTO> getDeadLetterTasks(int limit) {
        List<SettlementTaskDTO> tasks = new ArrayList<>();
        List<String> taskJsons = redisTemplate.opsForList()
                .range(settlementConfig.getDeadLetterQueueName(), 0, limit - 1);
        
        if (taskJsons != null) {
            for (String json : taskJsons) {
                try {
                    tasks.add(objectMapper.readValue(json, SettlementTaskDTO.class));
                } catch (JsonProcessingException e) {
                    log.warn("解析死信任务失败", e);
                }
            }
        }
        return tasks;
    }

    public boolean retryDeadLetterTask(String taskId) {
        String deadLetterQueue = settlementConfig.getDeadLetterQueueName();
        Long size = redisTemplate.opsForList().size(deadLetterQueue);
        
        if (size == null || size == 0) return false;

        for (long i = 0; i < size; i++) {
            String taskJson = redisTemplate.opsForList().index(deadLetterQueue, i);
            if (taskJson != null) {
                try {
                    SettlementTaskDTO task = objectMapper.readValue(taskJson, SettlementTaskDTO.class);
                    if (taskId.equals(task.getTaskId())) {
                        redisTemplate.opsForList().remove(deadLetterQueue, 1, taskJson);
                        task.setRetryCount(0);
                        task.setStatus("pending");
                        task.setLastError(null);
                        return enqueueTask(task);
                    }
                } catch (JsonProcessingException e) {
                    log.warn("解析死信任务失败", e);
                }
            }
        }
        return false;
    }
}
