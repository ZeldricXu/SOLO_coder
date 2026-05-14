package com.healthtrack.service;

import com.healthtrack.entity.HealthData;
import com.healthtrack.entity.HealthDataQueueTask;
import com.healthtrack.repository.HealthDataRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class HealthDataQueueWorker {

    private static final Logger logger = LoggerFactory.getLogger(HealthDataQueueWorker.class);

    @Autowired
    private HealthDataQueueService healthDataQueueService;

    @Autowired
    private HealthDataRepository healthDataRepository;

    @Autowired
    private IndicatorTrackingService indicatorTrackingService;

    @Autowired
    private GoalManagementService goalManagementService;

    @Autowired
    private AdvicePushService advicePushService;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private HistoryService historyService;

    private ExecutorService workerExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);

    @PostConstruct
    public void startWorker() {
        logger.info("启动健康数据队列Worker");
        running.set(true);
        shouldStop.set(false);
        workerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "health-data-worker");
            thread.setDaemon(true);
            return thread;
        });
        workerExecutor.submit(this::processQueue);
    }

    @PreDestroy
    public void stopWorker() {
        logger.info("停止健康数据队列Worker");
        shouldStop.set(true);
        running.set(false);
        if (workerExecutor != null) {
            workerExecutor.shutdown();
        }
    }

    private void processQueue() {
        logger.info("健康数据队列Worker开始处理");
        
        while (running.get() && !shouldStop.get()) {
            try {
                HealthDataQueueTask task = healthDataQueueService.dequeueTask();
                
                if (task == null) {
                    continue;
                }
                
                logger.info("处理健康数据任务: taskId={}, userId={}, dataType={}", 
                        task.getTaskId(), task.getUserId(), task.getDataType());
                
                try {
                    processTask(task);
                    logger.info("健康数据任务处理完成: taskId={}", task.getTaskId());
                    
                } catch (Exception e) {
                    logger.error("健康数据任务处理异常: taskId={}, error={}", 
                            task.getTaskId(), e.getMessage(), e);
                    
                    if (task.canRetry()) {
                        healthDataQueueService.retryTask(task);
                        logger.info("任务重新入队等待重试: taskId={}, retryCount={}", 
                                task.getTaskId(), task.getRetryCount() + 1);
                    } else {
                        healthDataQueueService.moveToDeadLetterQueue(task, "重试次数耗尽: " + e.getMessage());
                        logger.error("任务已达最大重试次数，移入死信队列: taskId={}", task.getTaskId());
                    }
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("健康数据队列Worker被中断");
                break;
            } catch (Exception e) {
                logger.error("健康数据队列处理异常", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        logger.info("健康数据队列Worker已停止");
    }

    private void processTask(HealthDataQueueTask task) {
        HealthData healthData = task.toHealthData();
        
        HealthData savedData = healthDataRepository.save(healthData);
        
        historyService.recordHistory(task.getUserId(), task.getDataType(), "DATA_PROCESSED",
                null, task.getDataValue(), "队列Worker处理健康数据: " + task.getDataType());
        
        indicatorTrackingService.updateIndicator(
                task.getUserId(), task.getDataType(), task.getDataValue());
        
        goalManagementService.checkGoals(task.getUserId(), task.getDataType(), task.getDataValue());
        
        advicePushService.generateAdviceIfNeeded(task.getUserId(), task.getDataType());
        
        statisticsService.updateStatistics(task.getUserId(), task.getDataType(), 
                "good".equals(task.getQuality()));
        
        logger.info("健康数据任务流程完成: taskId={}, dataId={}", 
                task.getTaskId(), savedData.getDataId());
    }

    public boolean isRunning() {
        return running.get();
    }

    public void triggerImmediateProcess() {
        if (workerExecutor != null && !workerExecutor.isShutdown()) {
            workerExecutor.submit(this::processPendingTasks);
        }
    }

    private void processPendingTasks() {
        long queueSize = healthDataQueueService.getQueueSize();
        if (queueSize > 0) {
            logger.info("主动触发处理队列中的待处理任务: queueSize={}", queueSize);
            for (int i = 0; i < queueSize; i++) {
                if (!running.get()) break;
                HealthDataQueueTask task = healthDataQueueService.dequeueTask();
                if (task != null) {
                    try {
                        processTask(task);
                    } catch (Exception e) {
                        if (task.canRetry()) {
                            healthDataQueueService.retryTask(task);
                        } else {
                            healthDataQueueService.moveToDeadLetterQueue(task, e.getMessage());
                        }
                    }
                }
            }
        }
    }
}
