package com.memberscore.service;

import com.memberscore.dto.BenefitTaskMessage;
import com.memberscore.queue.BenefitTaskQueue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
public class BenefitTaskWorker {
    
    private final BenefitTaskQueue benefitTaskQueue;
    private final BenefitService benefitService;
    
    @Value("${benefit.worker.thread-count:2}")
    private int threadCount;
    
    @Value("${benefit.worker.enabled:true}")
    private boolean workerEnabled;
    
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    @PostConstruct
    public void start() {
        if (!workerEnabled) {
            log.info("权益任务Worker已禁用");
            return;
        }
        
        benefitTaskQueue.recoverTasksOnStartup();
        
        executorService = Executors.newFixedThreadPool(threadCount);
        running.set(true);
        
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(this::processTasks);
        }
        
        log.info("权益任务Worker已启动: threadCount={}", threadCount);
    }
    
    @PreDestroy
    public void stop() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("权益任务Worker已停止");
    }
    
    private void processTasks() {
        while (running.get()) {
            try {
                BenefitTaskMessage message = benefitTaskQueue.pollTask();
                
                if (message == null) {
                    continue;
                }
                
                processSingleTask(message);
                
            } catch (Exception e) {
                log.error("处理权益任务时发生异常", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    
    private void processSingleTask(BenefitTaskMessage message) {
        log.info("开始处理权益任务: taskId={}, memberId={}, levelId={}", 
                message.getTaskId(), message.getMemberId(), message.getLevelId());
        
        try {
            benefitTaskQueue.markProcessing(message);
            
            benefitService.issueLevelBenefitsSync(
                    message.getMemberId(), 
                    message.getLevelId()
            );
            
            benefitTaskQueue.markCompleted(message);
            
            log.info("权益任务处理完成: taskId={}", message.getTaskId());
            
        } catch (Exception e) {
            log.error("权益任务处理失败: taskId={}", message.getTaskId(), e);
            benefitTaskQueue.markFailed(message, e.getMessage());
        }
    }
}
