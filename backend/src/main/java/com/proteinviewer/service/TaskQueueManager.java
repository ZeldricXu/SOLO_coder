package com.proteinviewer.service;

import com.proteinviewer.model.BatchTask;
import com.proteinviewer.model.TaskType;
import com.proteinviewer.repository.BatchTaskRepository;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@Service
public class TaskQueueManager {

    private static final Logger log = LoggerFactory.getLogger(TaskQueueManager.class);
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 300;
    private static final String WORKER_ID = UUID.randomUUID().toString();

    @Value("${batch.task.concurrency-limit:3}")
    private int concurrencyLimit;

    @Value("${batch.result.retention-days:30}")
    private int retentionDays;

    @Value("${minio.bucket:protein-files}")
    private String minioBucket;

    private final MinioClient minioClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final BatchTaskRepository batchTaskRepository;

    private final PriorityBlockingQueue<BatchTask> pendingQueue = new PriorityBlockingQueue<>(
            11, Comparator.comparingInt(BatchTask::getPriority).reversed().thenComparing(BatchTask::getCreatedAt)
    );
    private final ConcurrentHashMap<String, BatchTask> taskStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> resultStore = new ConcurrentHashMap<>();
    private final AtomicInteger runningCount = new AtomicInteger(0);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();

    private Function<BatchTask, Runnable> taskRecreator;

    public TaskQueueManager(MinioClient minioClient, SimpMessagingTemplate messagingTemplate, BatchTaskRepository batchTaskRepository) {
        this.minioClient = minioClient;
        this.messagingTemplate = messagingTemplate;
        this.batchTaskRepository = batchTaskRepository;
        startQueueWorker();
        startHeartbeatScheduler();
    }

    public void setTaskRecreator(Function<BatchTask, Runnable> taskRecreator) {
        this.taskRecreator = taskRecreator;
    }

    @PostConstruct
    public void recoverTasks() {
        log.info("Starting task recovery on startup... Worker ID: {}", WORKER_ID);

        List<BatchTask> pendingTasks = batchTaskRepository.findByStatusIn(Arrays.asList("PENDING", "QUEUED"));
        int pendingRecovered = 0;
        for (BatchTask task : pendingTasks) {
            task.setStatus("PENDING");
            taskStore.put(task.getTaskId(), task);
            pendingQueue.offer(task);
            pendingRecovered++;
            log.info("Recovered pending task: {}", task.getTaskId());
        }

        List<BatchTask> runningTasks = batchTaskRepository.findByStatusIn(Collections.singletonList("RUNNING"));
        int staleRecovered = 0;
        int stillRunning = 0;
        Instant heartbeatThreshold = Instant.now().minusSeconds(HEARTBEAT_TIMEOUT_SECONDS);

        for (BatchTask task : runningTasks) {
            if (task.getHeartbeatAt() == null || task.getHeartbeatAt().isBefore(heartbeatThreshold)) {
                task.setStatus("PENDING");
                task.setWorkerId(null);
                task.setHeartbeatAt(null);
                batchTaskRepository.save(task);
                taskStore.put(task.getTaskId(), task);
                pendingQueue.offer(task);
                staleRecovered++;
                log.info("Recovered stale task (dead worker): {}", task.getTaskId());
            } else {
                stillRunning++;
                log.info("Task {} has fresh heartbeat, assuming still running", task.getTaskId());
            }
        }

        log.info("Task recovery complete: {} pending recovered, {} stale tasks recovered, {} still running",
                pendingRecovered, staleRecovered, stillRunning);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down task queue manager...");
        heartbeatExecutor.shutdown();
        executor.shutdown();

        for (BatchTask task : taskStore.values()) {
            if ("RUNNING".equals(task.getStatus())) {
                log.info("Saving state of running task on shutdown: {}", task.getTaskId());
                batchTaskRepository.save(task);
            }
        }
    }

    public BatchTask submitTask(TaskType type, int totalCount, Runnable taskRunnable, Long submittedBy, String inputJson) {
        return submitTask(type, totalCount, taskRunnable, submittedBy, inputJson, 0);
    }

    public BatchTask submitTask(TaskType type, int totalCount, Runnable taskRunnable, Long submittedBy, String inputJson, int priority) {
        String taskId = UUID.randomUUID().toString();
        BatchTask task = new BatchTask(taskId, type, "PENDING", totalCount, submittedBy != null ? submittedBy : 1L);
        task.setTaskRunnable(taskRunnable);
        task.setInputJson(inputJson);
        task.setPriority(priority);
        task.setRetryCount(0);

        batchTaskRepository.save(task);
        taskStore.put(taskId, task);
        pendingQueue.offer(task);

        log.info("Task {} submitted: type={}, totalCount={}, priority={}, queueSize={}",
                taskId, type, totalCount, priority, pendingQueue.size());

        broadcastTaskUpdate(task);
        return task;
    }

    private void startQueueWorker() {
        new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    while (runningCount.get() >= concurrencyLimit) {
                        Thread.sleep(100);
                    }

                    BatchTask task = pendingQueue.poll(1, TimeUnit.SECONDS);
                    if (task != null) {
                        runningCount.incrementAndGet();
                        task.setStatus("RUNNING");
                        task.setWorkerId(WORKER_ID);
                        task.setHeartbeatAt(Instant.now());
                        batchTaskRepository.save(task);
                        broadcastTaskUpdate(task);

                        executor.submit(() -> {
                            try {
                                log.info("Starting task {} (type={}, worker={})", task.getTaskId(), task.getTaskType(), WORKER_ID);
                                long startTime = System.currentTimeMillis();

                                Runnable runnable = task.getTaskRunnable();
                                if (runnable == null && taskRecreator != null && task.getInputJson() != null) {
                                    runnable = taskRecreator.apply(task);
                                    task.setTaskRunnable(runnable);
                                }

                                if (runnable != null) {
                                    runnable.run();
                                }

                                long duration = System.currentTimeMillis() - startTime;
                                log.info("Task {} completed in {}ms", task.getTaskId(), duration);
                            } catch (Exception e) {
                                handleTaskFailure(task, e);
                            } finally {
                                runningCount.decrementAndGet();
                            }
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "task-queue-worker").start();
    }

    private void handleTaskFailure(BatchTask task, Exception e) {
        log.error("Task {} failed, retry count: {}", task.getTaskId(), task.getRetryCount(), e);

        if (task.getRetryCount() < 3) {
            task.setRetryCount(task.getRetryCount() + 1);
            task.setStatus("PENDING");
            task.setWorkerId(null);
            task.setHeartbeatAt(null);
            task.setErrorMessage("Retry " + task.getRetryCount() + ": " + e.getMessage());
            batchTaskRepository.save(task);
            pendingQueue.offer(task);
            log.info("Task {} re-queued for retry (attempt {})", task.getTaskId(), task.getRetryCount());
        } else {
            task.setStatus("FAILED");
            task.setErrorMessage("Max retries exceeded: " + e.getMessage());
            batchTaskRepository.save(task);
            broadcastTaskUpdate(task);
        }
    }

    private void startHeartbeatScheduler() {
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                Instant now = Instant.now();
                for (BatchTask task : taskStore.values()) {
                    if ("RUNNING".equals(task.getStatus()) && WORKER_ID.equals(task.getWorkerId())) {
                        task.setHeartbeatAt(now);
                        batchTaskRepository.save(task);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to update heartbeats", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    public Optional<BatchTask> getTask(String taskId) {
        return Optional.ofNullable(taskStore.get(taskId))
                .or(() -> batchTaskRepository.findByTaskId(taskId));
    }

    public Optional<Object> getResult(String taskId) {
        return Optional.ofNullable(resultStore.get(taskId));
    }

    public void storeResult(String taskId, Object result) {
        resultStore.put(taskId, result);
    }

    public int getQueueSize() {
        return pendingQueue.size();
    }

    public int getQueuePosition(String taskId) {
        BatchTask task = taskStore.get(taskId);
        if (task == null || "RUNNING".equals(task.getStatus()) || "COMPLETED".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
            return 0;
        }
        List<BatchTask> queueList = new ArrayList<>(pendingQueue);
        for (int i = 0; i < queueList.size(); i++) {
            if (queueList.get(i).getTaskId().equals(taskId)) {
                return i + 1;
            }
        }
        return 0;
    }

    public double estimateWaitSeconds(String taskId) {
        BatchTask task = taskStore.get(taskId);
        if (task == null) return 0.0;

        int position = getQueuePosition(taskId);
        if (position == 0) return 0.0;

        List<BatchTask> queueList = new ArrayList<>(pendingQueue);
        double totalWait = 0.0;

        int currentlyRunning = runningCount.get();
        double runningRemaining = 0.0;
        for (BatchTask t : taskStore.values()) {
            if ("RUNNING".equals(t.getStatus()) && t.getStartedAt() != null) {
                double elapsed = java.time.Duration.between(t.getStartedAt(), java.time.Instant.now()).toSeconds();
                double remaining = Math.max(0, t.getEstimatedDurationSeconds() - elapsed);
                runningRemaining = Math.max(runningRemaining, remaining);
            }
        }
        totalWait += runningRemaining;

        for (int i = 0; i < position - 1 && i < queueList.size(); i++) {
            totalWait += queueList.get(i).getEstimatedDurationSeconds();
        }

        return totalWait / Math.max(1, currentlyRunning);
    }

    public double estimateRemainingSeconds(String taskId) {
        BatchTask task = taskStore.get(taskId);
        if (task == null || task.getTotalCount() == 0) return 0.0;

        if ("PENDING".equals(task.getStatus()) || "QUEUED".equals(task.getStatus())) {
            return task.getEstimatedDurationSeconds() + estimateWaitSeconds(taskId);
        }

        if ("RUNNING".equals(task.getStatus()) && task.getStartedAt() != null) {
            double progress = (double) task.getCompletedCount() / task.getTotalCount();
            if (progress > 0) {
                double elapsed = java.time.Duration.between(task.getStartedAt(), java.time.Instant.now()).toSeconds();
                double totalEstimate = elapsed / progress;
                return Math.max(0, totalEstimate - elapsed);
            }
            return Math.max(0, task.getEstimatedDurationSeconds());
        }

        return 0.0;
    }

    public void broadcastTaskUpdate(BatchTask task) {
        try {
            com.proteinviewer.dto.BatchTaskStatusDto dto = toStatusDto(task);
            messagingTemplate.convertAndSend("/topic/batch/" + task.getTaskId(), dto);
            messagingTemplate.convertAndSend("/topic/batch/all", dto);
        } catch (Exception e) {
            log.warn("Failed to broadcast update for task {}", task.getTaskId(), e);
        }
    }

    public com.proteinviewer.dto.BatchTaskStatusDto toStatusDto(BatchTask task) {
        Object result = resultStore.get(task.getTaskId());
        return com.proteinviewer.dto.BatchTaskStatusDto.builder()
                .taskId(task.getTaskId())
                .status(task.getStatus())
                .taskType(task.getTaskType() != null ? task.getTaskType().getValue() : null)
                .totalCount(task.getTotalCount())
                .completedCount(task.getCompletedCount())
                .progress(task.getTotalCount() > 0 ? (double) task.getCompletedCount() / task.getTotalCount() : 0.0)
                .queuePosition(getQueuePosition(task.getTaskId()))
                .queueSize(getQueueSize())
                .estimatedWaitSeconds(estimateWaitSeconds(task.getTaskId()))
                .estimatedRemainingSeconds(estimateRemainingSeconds(task.getTaskId()))
                .errorMessage(task.getErrorMessage())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .startedAt(task.getStartedAt())
                .submittedBy(task.getSubmittedBy())
                .resultUrl("COMPLETED".equals(task.getStatus()) ?
                        "/api/structures/batch-analysis/" + task.getTaskId() + "/result" : null)
                .build();
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredTasks() {
        log.info("Starting cleanup of expired batch tasks (retention: {} days)", retentionDays);
        int cleanedCount = 0;

        Instant threshold = Instant.now().minus(java.time.Duration.ofDays(retentionDays));
        batchTaskRepository.deleteByCompletedAtBeforeAndStatus(threshold, "COMPLETED");

        Iterator<Map.Entry<String, BatchTask>> it = taskStore.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, BatchTask> entry = it.next();
            BatchTask task = entry.getValue();

            if (task.isExpired(retentionDays)) {
                try {
                    if (task.getResultStorageKey() != null) {
                        minioClient.removeObject(RemoveObjectArgs.builder()
                                .bucket(minioBucket)
                                .object(task.getResultStorageKey())
                                .build());
                        log.info("Deleted expired result from MinIO: {}", task.getResultStorageKey());
                    }

                    resultStore.remove(task.getTaskId());
                    it.remove();
                    cleanedCount++;

                    log.info("Cleaned up expired task {} (completed: {})",
                            task.getTaskId(), task.getCompletedAt());
                } catch (Exception e) {
                    log.warn("Failed to clean up task {}", task.getTaskId(), e);
                }
            }
        }

        log.info("Cleanup complete. Removed {} expired tasks", cleanedCount);
    }

    public List<com.proteinviewer.dto.BatchTaskStatusDto> getActiveTasks() {
        return taskStore.values().stream()
                .filter(t -> "PENDING".equals(t.getStatus()) || "QUEUED".equals(t.getStatus()) || "RUNNING".equals(t.getStatus()))
                .sorted(Comparator.comparing(BatchTask::getCreatedAt))
                .map(this::toStatusDto)
                .collect(java.util.stream.Collectors.toList());
    }

    public void updateTaskProgress(String taskId, int completedCount) {
        BatchTask task = taskStore.get(taskId);
        if (task != null) {
            task.setCompletedCount(completedCount);
            batchTaskRepository.save(task);
            broadcastTaskUpdate(task);
        }
    }

    public void markTaskComplete(String taskId, String storageKey, Object result) {
        BatchTask task = taskStore.get(taskId);
        if (task != null) {
            task.setStatus("COMPLETED");
            task.setResultStorageKey(storageKey);
            task.setCompletedCount(task.getTotalCount());
            batchTaskRepository.save(task);
            if (result != null) {
                storeResult(taskId, result);
            }
            broadcastTaskUpdate(task);
        }
    }

    public void markTaskFailed(String taskId, String errorMessage) {
        BatchTask task = taskStore.get(taskId);
        if (task != null) {
            task.setStatus("FAILED");
            task.setErrorMessage(errorMessage);
            batchTaskRepository.save(task);
            broadcastTaskUpdate(task);
        }
    }

    public String getWorkerId() {
        return WORKER_ID;
    }
}
