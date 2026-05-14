package com.taskscheduler.service;

import com.taskscheduler.config.FailHandlerConfig;
import com.taskscheduler.entity.FailRecord;
import com.taskscheduler.entity.TaskConfig;
import com.taskscheduler.repository.FailRecordRepository;
import com.taskscheduler.repository.TaskConfigRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class FailHandlerService {

    private final FailRecordRepository failRecordRepository;
    private final TaskConfigRepository taskConfigRepository;
    private final LogService logService;
    private final DispatcherService dispatcherService;
    private final FailHandlerConfig failHandlerConfig;

    private ExecutorService retryExecutor;
    private Semaphore retrySemaphore;
    private BlockingQueue<FailRecord> retryQueue;
    private final AtomicInteger activeRetries = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        if (failHandlerConfig.isEnableAsyncRetry()) {
            int poolSize = failHandlerConfig.getRetryThreadPoolSize();
            int maxConcurrent = failHandlerConfig.getMaxConcurrentRetries();
            int queueSize = poolSize * 10;

            retryExecutor = new ThreadPoolExecutor(
                    poolSize,
                    poolSize,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    new ThreadFactory() {
                        private final AtomicInteger counter = new AtomicInteger(0);
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "retry-worker-" + counter.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        }
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

            retrySemaphore = new Semaphore(maxConcurrent);
            retryQueue = new LinkedBlockingQueue<>(queueSize);

            startAsyncRetryProcessor();
            log.info("Async retry handler initialized with poolSize={}, maxConcurrent={}", poolSize, maxConcurrent);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (retryExecutor != null && !retryExecutor.isShutdown()) {
            retryExecutor.shutdown();
            try {
                if (!retryExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    retryExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                retryExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("Async retry handler shutdown completed");
        }
    }

    private void startAsyncRetryProcessor() {
        retryExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    FailRecord record = retryQueue.poll(1, TimeUnit.SECONDS);
                    if (record != null) {
                        if (retrySemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                            activeRetries.incrementAndGet();
                            retryExecutor.submit(() -> {
                                try {
                                    executeRetry(record);
                                } finally {
                                    retrySemaphore.release();
                                    activeRetries.decrementAndGet();
                                }
                            });
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in retry processor", e);
                }
            }
        });
    }

    @Transactional
    public FailRecord recordFailure(String executeId, String taskId, String failReason) {
        FailRecord failRecord = new FailRecord();
        failRecord.setTaskId(taskId);
        failRecord.setExecuteId(executeId);
        failRecord.setFailReason(failReason);
        failRecord.setRetryCount(0);
        failRecord.setStatus("retrying");
        failRecord.setNextRetryTime(LocalDateTime.now().plusSeconds(
                failHandlerConfig.getBaseRetryDelaySeconds()));

        FailRecord saved = failRecordRepository.save(failRecord);
        logService.logError(executeId, taskId, "Failure recorded: " + failReason);
        log.warn("Failure recorded for task: {}, executeId: {}, reason: {}", taskId, executeId, failReason);

        return saved;
    }

    @Transactional
    public void handleFailure(String executeId, String taskId, String failReason, int currentRetryNumber) {
        Optional<TaskConfig> taskOpt = taskConfigRepository.findByTaskId(taskId);
        if (taskOpt.isEmpty()) {
            log.error("Task not found for failure handling: {}", taskId);
            return;
        }

        TaskConfig taskConfig = taskOpt.get();
        int maxRetries = taskConfig.getRetryCount();

        if (currentRetryNumber < maxRetries) {
            scheduleRetryAsync(executeId, taskId, failReason, currentRetryNumber);
            sendAlert(taskId, "Task failed, scheduled for async retry. Attempt: " + (currentRetryNumber + 1));
        } else {
            markAsFinalFailure(executeId, taskId, failReason);
            sendAlert(taskId, "Task failed permanently after " + maxRetries + " retries. Reason: " + failReason);
        }
    }

    private void scheduleRetryAsync(String executeId, String taskId, String failReason, int currentRetryNumber) {
        int retryDelaySeconds = calculateRetryDelay(currentRetryNumber);
        
        FailRecord retryTask = createRetryTask(executeId, taskId, failReason, currentRetryNumber, retryDelaySeconds);
        
        if (failHandlerConfig.isEnableAsyncRetry() && retryQueue != null) {
            try {
                if (!retryQueue.offer(retryTask, 1, TimeUnit.SECONDS)) {
                    log.warn("Retry queue full, falling back to immediate processing for task: {}", taskId);
                    executeRetry(retryTask);
                } else {
                    log.info("Queued retry for task: {}, executeId: {}, delay: {}s, queueSize: {}",
                            taskId, executeId, retryDelaySeconds, retryQueue.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while queuing retry for task: {}", taskId);
            }
        } else {
            log.info("Async retry disabled, executing retry synchronously for task: {}", taskId);
            executeRetry(retryTask);
        }
    }

    @Transactional
    public FailRecord createRetryTask(String executeId, String taskId, String failReason, 
                                        int currentRetryNumber, int retryDelaySeconds) {
        Optional<FailRecord> failRecordOpt = failRecordRepository.findByExecuteId(executeId);
        
        FailRecord failRecord;
        if (failRecordOpt.isPresent()) {
            failRecord = failRecordOpt.get();
            failRecord.setRetryCount(currentRetryNumber + 1);
            failRecord.setStatus("retrying");
        } else {
            failRecord = new FailRecord();
            failRecord.setTaskId(taskId);
            failRecord.setExecuteId(executeId);
            failRecord.setFailReason(failReason);
            failRecord.setRetryCount(currentRetryNumber + 1);
            failRecord.setStatus("retrying");
        }

        failRecord.setNextRetryTime(LocalDateTime.now().plusSeconds(retryDelaySeconds));
        failRecord = failRecordRepository.save(failRecord);

        logService.logWarn(executeId, taskId, 
                "Scheduled async retry after " + retryDelaySeconds + " seconds, attempt: " + (currentRetryNumber + 1));
        log.info("Created retry task: {}, executeId: {}, attempt: {}, delay: {}s",
                taskId, executeId, currentRetryNumber + 1, retryDelaySeconds);

        return failRecord;
    }

    private int calculateRetryDelay(int retryCount) {
        int baseDelay = failHandlerConfig.getBaseRetryDelaySeconds();
        int maxDelay = failHandlerConfig.getMaxRetryDelaySeconds();
        int delay = baseDelay * (int) Math.pow(2, retryCount);
        return Math.min(delay, maxDelay);
    }

    @Transactional
    public void markAsFinalFailure(String executeId, String taskId, String failReason) {
        Optional<FailRecord> failRecordOpt = failRecordRepository.findByExecuteId(executeId);
        if (failRecordOpt.isPresent()) {
            FailRecord failRecord = failRecordOpt.get();
            failRecord.setStatus("failed");
            failRecord.setNextRetryTime(null);
            failRecordRepository.save(failRecord);
        }

        log.error("Task failed permanently: {}, executeId: {}, reason: {}", taskId, executeId, failReason);
        logService.logError(executeId, taskId, "Task failed permanently: " + failReason);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedRateString = "#{failHandlerConfig.retryScanIntervalSeconds * 1000}")
    @Transactional
    public void processPendingRetries() {
        LocalDateTime now = LocalDateTime.now();
        List<FailRecord> pendingRetries = failRecordRepository.findPendingRetries(now);

        if (pendingRetries.isEmpty()) {
            return;
        }

        log.info("Found {} pending retries to process", pendingRetries.size());

        for (FailRecord failRecord : pendingRetries) {
            if (failHandlerConfig.isEnableAsyncRetry() && retryQueue != null) {
                try {
                    if (!retryQueue.offer(failRecord, 500, TimeUnit.MILLISECONDS)) {
                        log.warn("Retry queue full, skipping retry for now: {}", failRecord.getTaskId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                retryExecutor.submit(() -> executeRetry(failRecord));
            }
        }
    }

    private void executeRetry(FailRecord failRecord) {
        String taskId = failRecord.getTaskId();
        String executeId = failRecord.getExecuteId();

        LocalDateTime scheduledTime = failRecord.getNextRetryTime();
        if (scheduledTime != null && scheduledTime.isAfter(LocalDateTime.now())) {
            long delay = java.time.Duration.between(LocalDateTime.now(), scheduledTime).toMillis();
            if (delay > 0) {
                try {
                    log.debug("Delaying retry for {}ms until scheduled time", delay);
                    Thread.sleep(Math.min(delay, 60000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        try {
            log.info("Executing retry for task: {}, executeId: {}, attempt: {}",
                    taskId, executeId, failRecord.getRetryCount());
            dispatcherService.triggerAndDispatch(taskId, "retry");
            updateRetrySuccess(failRecord);
        } catch (Exception e) {
            log.error("Retry execution failed for task: {}, error: {}", taskId, e.getMessage(), e);
            handleRetryFailure(failRecord, e);
        }
    }

    @Transactional
    public void updateRetrySuccess(FailRecord failRecord) {
        failRecord.setStatus("retried");
        failRecordRepository.save(failRecord);
        log.info("Retry success for task: {}, executeId: {}", failRecord.getTaskId(), failRecord.getExecuteId());
    }

    @Transactional
    public void handleRetryFailure(FailRecord failRecord, Exception e) {
        Optional<TaskConfig> taskOpt = taskConfigRepository.findByTaskId(failRecord.getTaskId());
        if (taskOpt.isPresent()) {
            TaskConfig taskConfig = taskOpt.get();
            int maxRetries = taskConfig.getRetryCount();
            int currentRetry = failRecord.getRetryCount();

            if (currentRetry < maxRetries) {
                int newDelay = calculateRetryDelay(currentRetry);
                failRecord.setNextRetryTime(LocalDateTime.now().plusSeconds(newDelay));
                failRecord.setStatus("retrying");
                failRecordRepository.save(failRecord);

                logService.logWarn(failRecord.getExecuteId(), failRecord.getTaskId(),
                        "Retry attempt " + currentRetry + " failed, next retry in " + newDelay + "s");
            } else {
                markAsFinalFailure(failRecord.getExecuteId(), failRecord.getTaskId(), e.getMessage());
            }
        }
    }

    private void sendAlert(String taskId, String message) {
        log.error("ALERT - Task: {}, Message: {}", taskId, message);
    }

    public List<FailRecord> getFailRecordsByTaskId(String taskId) {
        return failRecordRepository.findByTaskIdOrderByCreatedAtDesc(taskId);
    }

    public Optional<FailRecord> getFailRecordByExecuteId(String executeId) {
        return failRecordRepository.findByExecuteId(executeId);
    }

    public long countFailedRecords(String taskId) {
        return failRecordRepository.countFailedRecords(taskId);
    }

    public int getActiveRetryCount() {
        return activeRetries.get();
    }

    public int getQueuedRetryCount() {
        return retryQueue != null ? retryQueue.size() : 0;
    }

    public int getAvailableRetryPermits() {
        return retrySemaphore != null ? retrySemaphore.availablePermits() : 0;
    }

    public boolean isAsyncRetryEnabled() {
        return failHandlerConfig.isEnableAsyncRetry();
    }
}
