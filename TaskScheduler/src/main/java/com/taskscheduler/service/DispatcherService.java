package com.taskscheduler.service;

import com.taskscheduler.config.SchedulerConfig;
import com.taskscheduler.dto.ExecuteResult;
import com.taskscheduler.entity.ExecuteRecord;
import com.taskscheduler.entity.Executor;
import com.taskscheduler.entity.TaskConfig;
import com.taskscheduler.repository.ExecuteRecordRepository;
import com.taskscheduler.repository.TaskConfigRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class DispatcherService {

    private final TaskConfigRepository taskConfigRepository;
    private final ExecuteRecordRepository executeRecordRepository;
    private final ExecutorManagerService executorManagerService;
    private final DependencyService dependencyService;
    private final LogService logService;
    private final TaskExecutorService taskExecutorService;
    private final FailHandlerService failHandlerService;
    private final SchedulerConfig schedulerConfig;

    private ExecutorService dispatchExecutor;
    private Semaphore dispatchSemaphore;
    private final Map<String, Queue<DispatchTask>> taskDispatchQueues = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> taskDispatchTimers = new ConcurrentHashMap<>();
    private ScheduledExecutorService schedulerExecutor;
    private final AtomicInteger activeDispatches = new AtomicInteger(0);
    private final AtomicLong totalDispatches = new AtomicLong(0);

    @PostConstruct
    public void init() {
        if (schedulerConfig.isEnableParallelDispatch()) {
            int poolSize = schedulerConfig.getDispatchThreadPoolSize();
            int maxConcurrent = schedulerConfig.getMaxConcurrentDispatches();

            dispatchExecutor = new ThreadPoolExecutor(
                    poolSize,
                    poolSize,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    new ThreadFactory() {
                        private final AtomicInteger counter = new AtomicInteger(0);
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "dispatch-worker-" + counter.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        }
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

            dispatchSemaphore = new Semaphore(maxConcurrent);
            schedulerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dispatch-scheduler");
                t.setDaemon(true);
                return t;
            });

            startBatchDispatchProcessor();
            log.info("Parallel dispatch initialized with poolSize={}, maxConcurrent={}", poolSize, maxConcurrent);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (schedulerExecutor != null && !schedulerExecutor.isShutdown()) {
            schedulerExecutor.shutdownNow();
        }
        if (dispatchExecutor != null && !dispatchExecutor.isShutdown()) {
            dispatchExecutor.shutdown();
            try {
                if (!dispatchExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    dispatchExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                dispatchExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("Parallel dispatch shutdown completed, total dispatches: {}", totalDispatches.get());
        }
    }

    private void startBatchDispatchProcessor() {
        schedulerExecutor.scheduleWithFixedDelay(
                this::processBatchDispatches,
                schedulerConfig.getBatchDispatchIntervalMs(),
                schedulerConfig.getBatchDispatchIntervalMs(),
                TimeUnit.MILLISECONDS
        );
    }

    private void processBatchDispatches() {
        for (Map.Entry<String, Queue<DispatchTask>> entry : taskDispatchQueues.entrySet()) {
            Queue<DispatchTask> queue = entry.getValue();
            List<DispatchTask> batch = new ArrayList<>();

            DispatchTask task;
            while ((task = queue.poll()) != null && batch.size() < schedulerConfig.getMaxBatchSize()) {
                batch.add(task);
            }

            if (!batch.isEmpty()) {
                dispatchBatch(batch);
            }
        }
    }

    private void dispatchBatch(List<DispatchTask> batch) {
        log.debug("Dispatching batch of {} tasks", batch.size());
        for (DispatchTask task : batch) {
            submitDispatch(task.taskConfig, task.executeRecord);
        }
    }

    @Transactional
    public ExecuteRecord createExecuteRecord(String taskId, String triggerType) {
        String executeId = "exec_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000);

        ExecuteRecord record = new ExecuteRecord();
        record.setExecuteId(executeId);
        record.setTaskId(taskId);
        record.setExecuteTime(LocalDateTime.now());
        record.setExecuteStatus("pending");
        record.setTriggerType(triggerType);
        record.setRetryNumber(0);

        executeRecordRepository.save(record);
        log.info("Created execute record: {} for task: {}", executeId, taskId);

        return record;
    }

    @Transactional
    public ExecuteRecord triggerAndDispatch(String taskId, String triggerType) {
        TaskConfig taskConfig = taskConfigRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (!taskConfig.getEnabled()) {
            throw new IllegalStateException("Task is disabled: " + taskId);
        }

        if (!dependencyService.checkDependenciesCompleted(taskId)) {
            log.warn("Dependencies not completed for task: {}, delaying execution", taskId);
            ExecuteRecord delayed = createExecuteRecord(taskId, triggerType);
            delayed.setExecuteStatus("delayed");
            executeRecordRepository.save(delayed);
            logService.logWarn(delayed.getExecuteId(), taskId, "Dependencies not completed, task delayed");
            return delayed;
        }

        long runningCount = executeRecordRepository.countByTaskIdAndStatus(taskId, "running");
        if (runningCount >= taskConfig.getMaxConcurrent()) {
            log.warn("Max concurrent execution reached for task: {}", taskId);
            throw new IllegalStateException("Max concurrent execution reached for task: " + taskId);
        }

        ExecuteRecord record = createExecuteRecord(taskId, triggerType);

        if (schedulerConfig.isEnableParallelDispatch() && schedulerConfig.isEnableBatchDispatch()) {
            queueForBatchDispatch(taskConfig, record);
        } else {
            submitDispatch(taskConfig, record);
        }

        log.info("Task triggered: {}, executeId: {}, type: {}", taskId, record.getExecuteId(), triggerType);
        return record;
    }

    private void queueForBatchDispatch(TaskConfig taskConfig, ExecuteRecord executeRecord) {
        String taskId = taskConfig.getTaskId();
        DispatchTask dispatchTask = new DispatchTask(taskConfig, executeRecord);

        Queue<DispatchTask> queue = taskDispatchQueues.computeIfAbsent(taskId,
                k -> new ConcurrentLinkedQueue<>());

        queue.offer(dispatchTask);
        log.debug("Queued task for batch dispatch: {}, queue size: {}", taskId, queue.size());

        scheduleBatchDispatch(taskId);
    }

    private void scheduleBatchDispatch(String taskId) {
        if (!taskDispatchTimers.containsKey(taskId)) {
            ScheduledFuture<?> future = schedulerExecutor.schedule(() -> {
                taskDispatchTimers.remove(taskId);
                Queue<DispatchTask> queue = taskDispatchQueues.get(taskId);
                if (queue != null && !queue.isEmpty()) {
                    processBatchDispatches();
                }
            }, schedulerConfig.getBatchDispatchIntervalMs(), TimeUnit.MILLISECONDS);
            taskDispatchTimers.put(taskId, future);
        }
    }

    private void submitDispatch(TaskConfig taskConfig, ExecuteRecord executeRecord) {
        if (schedulerConfig.isEnableParallelDispatch() && dispatchSemaphore != null) {
            try {
                if (dispatchSemaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                    activeDispatches.incrementAndGet();
                    totalDispatches.incrementAndGet();
                    dispatchExecutor.submit(() -> {
                        try {
                            dispatchAndExecute(taskConfig, executeRecord);
                        } finally {
                            dispatchSemaphore.release();
                            activeDispatches.decrementAndGet();
                        }
                    });
                } else {
                    log.warn("Dispatch semaphore busy, using direct dispatch for task: {}", taskConfig.getTaskId());
                    directDispatch(taskConfig, executeRecord);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while acquiring dispatch semaphore");
                directDispatch(taskConfig, executeRecord);
            }
        } else {
            directDispatch(taskConfig, executeRecord);
        }
    }

    private void directDispatch(TaskConfig taskConfig, ExecuteRecord executeRecord) {
        totalDispatches.incrementAndGet();
        if (schedulerConfig.isEnableParallelDispatch()) {
            new Thread(() -> dispatchAndExecute(taskConfig, executeRecord),
                    "dispatch-direct-" + taskConfig.getTaskId()).start();
        } else {
            dispatchAndExecute(taskConfig, executeRecord);
        }
    }

    public void dispatchAndExecute(TaskConfig taskConfig, ExecuteRecord executeRecord) {
        String executeId = executeRecord.getExecuteId();
        String taskId = taskConfig.getTaskId();
        Executor selectedExecutor = null;

        try {
            logService.logInfo(executeId, taskId, "Dispatching task for execution");

            selectedExecutor = executorManagerService.selectExecutor(taskConfig.getTaskType());

            if (selectedExecutor == null) {
                logService.logWarn(executeId, taskId, "No available executor, task delayed");
                updateExecuteStatus(executeId, "delayed");
                return;
            }

            updateExecuteStatus(executeId, "running");
            updateExecutor(executeId, selectedExecutor.getExecutorId());

            logService.logInfo(executeId, taskId, "Task dispatched to executor: " + selectedExecutor.getExecutorId()
                    + " (load: " + selectedExecutor.getCurrentLoad() + "/" + selectedExecutor.getMaxCapacity() + ")");

            ExecuteResult result = taskExecutorService.executeTask(taskConfig, executeId);

            updateSuccess(executeId, result.getResult(), result.getDurationSeconds());
            executorManagerService.releaseExecutor(selectedExecutor.getExecutorId());
            logService.logInfo(executeId, taskId, "Task completed successfully. Duration: " + result.getDurationSeconds() + "s");

        } catch (Exception e) {
            log.error("Task execution failed: {}", taskId, e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";

            updateFailure(executeId, errorMsg);
            failHandlerService.recordFailure(executeId, taskId, errorMsg);

            if (selectedExecutor != null) {
                executorManagerService.releaseExecutor(selectedExecutor.getExecutorId());
            }

            failHandlerService.handleFailure(executeId, taskId, errorMsg, executeRecord.getRetryNumber());
            logService.logError(executeId, taskId, "Task failed: " + errorMsg);
        }
    }

    @Transactional
    public void updateExecuteStatus(String executeId, String status) {
        executeRecordRepository.findByExecuteId(executeId).ifPresent(record -> {
            record.setExecuteStatus(status);
            if ("running".equals(status)) {
                record.setStartTime(LocalDateTime.now());
            }
            executeRecordRepository.save(record);
        });
    }

    @Transactional
    public void updateExecutor(String executeId, String executorId) {
        executeRecordRepository.findByExecuteId(executeId).ifPresent(record -> {
            record.setExecutorId(executorId);
            executeRecordRepository.save(record);
        });
    }

    @Transactional
    public void updateSuccess(String executeId, String result, long duration) {
        executeRecordRepository.findByExecuteId(executeId).ifPresent(record -> {
            record.setExecuteStatus("success");
            record.setExecuteResult(result);
            record.setExecuteDurationSeconds(duration);
            record.setEndTime(LocalDateTime.now());
            executeRecordRepository.save(record);
        });
    }

    @Transactional
    public void updateFailure(String executeId, String errorMessage) {
        executeRecordRepository.findByExecuteId(executeId).ifPresent(record -> {
            record.setExecuteStatus("failed");
            record.setExecuteResult(errorMessage);
            record.setEndTime(LocalDateTime.now());
            executeRecordRepository.save(record);
        });
    }

    public int getActiveDispatchCount() {
        return activeDispatches.get();
    }

    public long getTotalDispatchCount() {
        return totalDispatches.get();
    }

    public int getAvailableDispatchPermits() {
        return dispatchSemaphore != null ? dispatchSemaphore.availablePermits() : 0;
    }

    public int getQueuedTaskCount() {
        int total = 0;
        for (Queue<DispatchTask> queue : taskDispatchQueues.values()) {
            total += queue.size();
        }
        return total;
    }

    public boolean isParallelDispatchEnabled() {
        return schedulerConfig.isEnableParallelDispatch();
    }

    private static class DispatchTask {
        final TaskConfig taskConfig;
        final ExecuteRecord executeRecord;

        DispatchTask(TaskConfig taskConfig, ExecuteRecord executeRecord) {
            this.taskConfig = taskConfig;
            this.executeRecord = executeRecord;
        }
    }
}
