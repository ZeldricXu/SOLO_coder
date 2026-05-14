package com.stockmgmt.service;

import com.stockmgmt.dto.InboundRequest;
import com.stockmgmt.dto.OutboundRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Optional;

@Service
public class AsyncStockService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncStockService.class);

    @Autowired(required = false)
    private RedisStockTaskQueueService redisStockTaskQueueService;

    @Autowired
    private InboundOutboundService inboundOutboundService;

    private boolean useRedis = true;

    @PostConstruct
    public void init() {
        if (redisStockTaskQueueService == null) {
            logger.warn("Redis库存任务队列服务不可用，将使用内存模式");
            useRedis = false;
        } else {
            logger.info("Redis库存任务队列服务已就绪");
        }
    }

    public String submitInboundTask(InboundRequest request) {
        if (useRedis) {
            return redisStockTaskQueueService.submitInboundTask(request);
        }
        return submitInboundTaskMemory(request);
    }

    public String submitOutboundTask(OutboundRequest request) {
        if (useRedis) {
            return redisStockTaskQueueService.submitOutboundTask(request);
        }
        return submitOutboundTaskMemory(request);
    }

    public Optional<?> getTaskStatus(String taskId) {
        if (useRedis) {
            return redisStockTaskQueueService.getTaskStatus(taskId);
        }
        return Optional.empty();
    }

    public long getPendingTaskCount() {
        if (useRedis) {
            return redisStockTaskQueueService.getPendingTaskCount();
        }
        return 0;
    }

    public long getProcessingTaskCount() {
        if (useRedis) {
            return redisStockTaskQueueService.getProcessingTaskCount();
        }
        return 0;
    }

    public void recoverFailedTasks() {
        if (useRedis) {
            redisStockTaskQueueService.recoverFailedTasks();
        }
    }

    @Async
    public void executeInboundAsync(InboundRequest request, String taskId) {
        logger.info("异步执行入库任务: taskId={}", taskId);
        try {
            inboundOutboundService.inbound(request);
            logger.info("异步入库任务执行成功: taskId={}", taskId);
        } catch (Exception e) {
            logger.error("异步入库任务执行失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    @Async
    public void executeOutboundAsync(OutboundRequest request, String taskId) {
        logger.info("异步执行出库任务: taskId={}", taskId);
        try {
            inboundOutboundService.outbound(request);
            logger.info("异步出库任务执行成功: taskId={}", taskId);
        } catch (Exception e) {
            logger.error("异步出库任务执行失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    private String submitInboundTaskMemory(InboundRequest request) {
        String taskId = generateTaskId();
        executeInboundAsync(request, taskId);
        logger.info("入库任务已提交（内存模式）: taskId={}", taskId);
        return taskId;
    }

    private String submitOutboundTaskMemory(OutboundRequest request) {
        String taskId = generateTaskId();
        executeOutboundAsync(request, taskId);
        logger.info("出库任务已提交（内存模式）: taskId={}", taskId);
        return taskId;
    }

    private String generateTaskId() {
        return "TASK_" + System.currentTimeMillis() + "_" + 
               java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
