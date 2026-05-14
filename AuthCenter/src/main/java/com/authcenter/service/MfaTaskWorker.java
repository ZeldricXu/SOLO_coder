package com.authcenter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class MfaTaskWorker {
    
    private static final Logger logger = LoggerFactory.getLogger(MfaTaskWorker.class);
    
    @Autowired
    private MfaTaskQueueService taskQueueService;
    
    @Autowired
    private AuditService auditService;
    
    @Value("${mfa.worker.enabled:true}")
    private boolean workerEnabled;
    
    @Value("${mfa.worker.threads:2}")
    private int workerThreads;
    
    @Value("${mfa.worker.poll-interval:1000}")
    private long pollInterval;
    
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    @PostConstruct
    public void start() {
        if (workerEnabled) {
            logger.info("Starting MFA Task Worker with {} threads", workerThreads);
            executorService = Executors.newFixedThreadPool(workerThreads);
            running.set(true);
            
            for (int i = 0; i < workerThreads; i++) {
                final int workerId = i;
                executorService.submit(() -> runWorker(workerId));
            }
            
            logger.info("MFA Task Worker started successfully");
        } else {
            logger.info("MFA Task Worker is disabled");
        }
    }
    
    @PreDestroy
    public void stop() {
        logger.info("Stopping MFA Task Worker...");
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
        
        logger.info("MFA Task Worker stopped");
    }
    
    private void runWorker(int workerId) {
        logger.debug("MFA Worker {} started", workerId);
        
        while (running.get()) {
            try {
                MfaTaskQueueService.MfaTask task = taskQueueService.claimTask();
                
                if (task != null) {
                    logger.debug("Worker {} processing task: {}", workerId, task.getTaskId());
                    processTask(task);
                } else {
                    try {
                        Thread.sleep(pollInterval);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                
            } catch (Exception e) {
                logger.error("Worker {} encountered error: {}", workerId, e.getMessage(), e);
                try {
                    Thread.sleep(pollInterval);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        logger.debug("MFA Worker {} stopped", workerId);
    }
    
    private void processTask(MfaTaskQueueService.MfaTask task) {
        long startTime = System.currentTimeMillis();
        
        try {
            boolean success = executeSend(task);
            
            if (success) {
                taskQueueService.completeTask(task);
                long duration = System.currentTimeMillis() - startTime;
                logger.info("Task {} completed successfully in {}ms", task.getTaskId(), duration);
                
                auditService.log(
                    task.getUserId(),
                    "mfa_sent",
                    "success",
                    task.getIpAddress(),
                    task.getUserAgent(),
                    "MFA code sent via " + task.getMfaType() + " to " + maskTarget(task.getTarget())
                );
            } else {
                taskQueueService.failTask(task, "send_failed");
                logger.warn("Task {} failed to send, retry count: {}", task.getTaskId(), task.getRetryCount());
            }
            
        } catch (Exception e) {
            logger.error("Task {} execution error: {}", task.getTaskId(), e.getMessage(), e);
            taskQueueService.failTask(task, e.getMessage());
        }
    }
    
    private boolean executeSend(MfaTaskQueueService.MfaTask task) {
        String mfaType = task.getMfaType();
        String target = task.getTarget();
        String code = task.getMfaCode();
        
        try {
            switch (mfaType.toLowerCase()) {
                case "sms":
                    return sendSms(target, code);
                case "email":
                    return sendEmail(target, code);
                default:
                    logger.warn("Unknown MFA type: {}", mfaType);
                    return false;
            }
        } catch (Exception e) {
            logger.error("Error sending {} code to {}: {}", mfaType, target, e.getMessage(), e);
            return false;
        }
    }
    
    private boolean sendSms(String phone, String code) {
        logger.info("[SMS] Sending verification code to {}: {}", maskPhone(phone), code);
        return true;
    }
    
    private boolean sendEmail(String email, String code) {
        logger.info("[EMAIL] Sending verification code to {}: {}", maskEmail(email), code);
        return true;
    }
    
    private String maskTarget(String target) {
        if (target == null) return "null";
        if (target.contains("@")) {
            return maskEmail(target);
        }
        return maskPhone(target);
    }
    
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
    
    private String maskEmail(String email) {
        if (email == null) return "***";
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) return "***" + email.substring(atIndex);
        return email.substring(0, 2) + "***" + email.substring(atIndex);
    }
    
    @Scheduled(fixedDelay = 60000)
    public void cleanupStaleTasks() {
        if (workerEnabled && running.get()) {
            logger.debug("Running stale task cleanup...");
            taskQueueService.cleanupStaleProcessingTasks();
        }
    }
    
    public long getPendingTaskCount() {
        return taskQueueService.getPendingTaskCount();
    }
    
    public long getProcessingTaskCount() {
        return taskQueueService.getProcessingTaskCount();
    }
    
    public long getDeadLetterCount() {
        return taskQueueService.getDeadLetterCount();
    }
    
    public boolean isRunning() {
        return running.get();
    }
}