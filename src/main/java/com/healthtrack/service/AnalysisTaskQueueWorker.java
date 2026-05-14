package com.healthtrack.service;

import com.healthtrack.entity.AnalysisTask;
import com.healthtrack.repository.HealthIndicatorRepository;
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
public class AnalysisTaskQueueWorker {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisTaskQueueWorker.class);

    @Autowired
    private AnalysisTaskQueueService analysisTaskQueueService;

    @Autowired
    private AsyncIndicatorAnalysisService asyncIndicatorAnalysisService;

    @Autowired
    private HealthIndicatorRepository healthIndicatorRepository;

    private ExecutorService workerExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);

    @PostConstruct
    public void startWorker() {
        logger.info("启动分析任务队列Worker");
        running.set(true);
        shouldStop.set(false);
        workerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "analysis-task-worker");
            thread.setDaemon(true);
            return thread;
        });
        workerExecutor.submit(this::processQueue);
    }

    @PreDestroy
    public void stopWorker() {
        logger.info("停止分析任务队列Worker");
        shouldStop.set(true);
        running.set(false);
        if (workerExecutor != null) {
            workerExecutor.shutdown();
        }
    }

    private void processQueue() {
        logger.info("分析任务队列Worker开始处理");
        
        while (running.get() && !shouldStop.get()) {
            try {
                AnalysisTask task = analysisTaskQueueService.dequeueTask();
                
                if (task == null) {
                    continue;
                }
                
                logger.info("处理分析任务: taskId={}, userId={}, dataType={}", 
                        task.getTaskId(), task.getUserId(), task.getDataType());
                
                try {
                    processTask(task);
                    analysisTaskQueueService.markTaskCompleted(task);
                    logger.info("分析任务处理完成: taskId={}", task.getTaskId());
                    
                } catch (Exception e) {
                    logger.error("分析任务处理异常: taskId={}, error={}", 
                            task.getTaskId(), e.getMessage(), e);
                    analysisTaskQueueService.markTaskFailed(task);
                    
                    if (task.canRetry()) {
                        analysisTaskQueueService.retryTask(task);
                        logger.info("分析任务重新入队等待重试: taskId={}, retryCount={}", 
                                task.getTaskId(), task.getRetryCount() + 1);
                    } else {
                        analysisTaskQueueService.moveToDeadLetterQueue(task, "重试次数耗尽: " + e.getMessage());
                        logger.error("分析任务已达最大重试次数，移入死信队列: taskId={}", task.getTaskId());
                    }
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("分析任务队列Worker被中断");
                break;
            } catch (Exception e) {
                logger.error("分析任务队列处理异常", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        logger.info("分析任务队列Worker已停止");
    }

    private void processTask(AnalysisTask task) throws Exception {
        logger.info("执行分析任务: userId={}, dataType={}, value={}", 
                task.getUserId(), task.getDataType(), task.getCurrentValue());
        
        asyncIndicatorAnalysisService.analyzeIndicatorAsync(
                task.getUserId(), 
                task.getDataType(), 
                task.getCurrentValue()
        ).get();
        
        logger.info("分析任务执行成功: taskId={}", task.getTaskId());
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
        long queueSize = analysisTaskQueueService.getQueueSize();
        if (queueSize > 0) {
            logger.info("主动触发处理队列中的待处理分析任务: queueSize={}", queueSize);
            for (int i = 0; i < queueSize; i++) {
                if (!running.get()) break;
                AnalysisTask task = analysisTaskQueueService.dequeueTask();
                if (task != null) {
                    try {
                        processTask(task);
                        analysisTaskQueueService.markTaskCompleted(task);
                    } catch (Exception e) {
                        analysisTaskQueueService.markTaskFailed(task);
                        if (task.canRetry()) {
                            analysisTaskQueueService.retryTask(task);
                        } else {
                            analysisTaskQueueService.moveToDeadLetterQueue(task, e.getMessage());
                        }
                    }
                }
            }
        }
    }
}
