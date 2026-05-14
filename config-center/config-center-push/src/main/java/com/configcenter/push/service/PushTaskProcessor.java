package com.configcenter.push.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class PushTaskProcessor {

    private final AsyncPushService asyncPushService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread processorThread;

    @PostConstruct
    public void init() {
        log.info("Initializing PushTaskProcessor");
        startProcessor();
    }

    @PreDestroy
    public void destroy() {
        log.info("Stopping PushTaskProcessor");
        stopProcessor();
    }

    public void startProcessor() {
        if (running.compareAndSet(false, true)) {
            processorThread = new Thread(this::processQueueLoop, "push-task-processor");
            processorThread.setDaemon(true);
            processorThread.start();
            log.info("PushTaskProcessor thread started");
        }
    }

    public void stopProcessor() {
        if (running.compareAndSet(true, false)) {
            if (processorThread != null) {
                processorThread.interrupt();
            }
            log.info("PushTaskProcessor thread stopped");
        }
    }

    private void processQueueLoop() {
        log.info("Push task processor loop started");
        
        while (running.get()) {
            try {
                asyncPushService.processQueue();
                Thread.sleep(100);
            } catch (InterruptedException e) {
                log.info("Push task processor interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in push task processor loop", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        log.info("Push task processor loop ended");
    }

    @Scheduled(fixedRate = 5000)
    public void reportQueueStatus() {
        int queueSize = asyncPushService.getQueueSize();
        if (queueSize > 0) {
            log.debug("Push task queue status: {} pending tasks", queueSize);
        }
    }

    public Map<String, Object> getProcessorStatus() {
        Map<String, Object> status = new java.util.HashMap<>();
        status.put("running", running.get());
        status.put("queueSize", asyncPushService.getQueueSize());
        status.put("threadAlive", processorThread != null && processorThread.isAlive());
        return status;
    }
}
