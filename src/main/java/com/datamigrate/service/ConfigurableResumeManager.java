package com.datamigrate.service;

import com.datamigrate.common.CheckpointType;
import com.datamigrate.common.ResumeStrategy;
import com.datamigrate.config.ResumeConfig;
import com.datamigrate.entity.MigrateProgress;
import com.datamigrate.entity.MigrateTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigurableResumeManager {

    private final ProgressService progressService;
    private final TaskService taskService;

    private final Set<String> processedKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> taskResumePoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> recordsSinceLastCheckpoint = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDateTime> lastCheckpointTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ResumeConfig> taskConfigs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<Long>> completedBatches = new ConcurrentHashMap<>();

    public void initTask(String taskId, MigrateTask task) {
        ResumeConfig config = ResumeConfig.fromTask(task);
        taskConfigs.put(taskId, config);
        recordsSinceLastCheckpoint.put(taskId, new AtomicLong(0));
        lastCheckpointTime.put(taskId, LocalDateTime.now());
        completedBatches.put(taskId, ConcurrentHashMap.newKeySet());
        log.info("初始化断点续传配置: taskId={}, strategy={}, checkpointType={}",
            taskId, config.getResumeStrategy(), config.getCheckpointType());
    }

    public ResumeConfig getTaskConfig(String taskId) {
        return taskConfigs.computeIfAbsent(taskId, k -> ResumeConfig.getDefault());
    }

    public ResumeManager.ResumeState getResumeState(String taskId) {
        ResumeConfig config = getTaskConfig(taskId);
        
        if (!config.isEnabled()) {
            log.info("断点续传已禁用，任务将从头开始: taskId={}", taskId);
            return new ResumeManager.ResumeState(false, 0, null, 0, 0, 0);
        }

        Optional<MigrateProgress> progressOpt = progressService.getProgress(taskId);
        
        if (progressOpt.isPresent()) {
            MigrateProgress progress = progressOpt.get();
            
            switch (config.getResumeStrategy()) {
                case FULL_RESTART:
                    log.info("续传策略为FULL_RESTART，从头开始迁移: taskId={}", taskId);
                    progressService.resetProgress(taskId);
                    return new ResumeManager.ResumeState(false, 0, null, 0, 0, 0);
                
                case SKIP_COMPLETED_BATCHES:
                    if (progress.getCurrentPosition() > 0) {
                        log.info("续传策略为SKIP_COMPLETED_BATCHES，从断点位置继续: taskId={}, position={}",
                            taskId, progress.getCurrentPosition());
                        return new ResumeManager.ResumeState(
                            true,
                            progress.getCurrentPosition(),
                            progress.getLastProcessedKey(),
                            progress.getMigratedRecords(),
                            progress.getSuccessRecords(),
                            progress.getFailRecords()
                        );
                    }
                    break;
                
                case FROM_BREAKPOINT:
                default:
                    if (progress.getCurrentPosition() > 0) {
                        log.info("续传策略为FROM_BREAKPOINT，从断点位置继续: taskId={}, position={}",
                            taskId, progress.getCurrentPosition());
                        return new ResumeManager.ResumeState(
                            true,
                            progress.getCurrentPosition(),
                            progress.getLastProcessedKey(),
                            progress.getMigratedRecords(),
                            progress.getSuccessRecords(),
                            progress.getFailRecords()
                        );
                    }
                    break;
            }
        }
        
        return new ResumeManager.ResumeState(false, 0, null, 0, 0, 0);
    }

    public void recordProcessed(String taskId, String recordKey) {
        processedKeys.add(taskId + ":" + recordKey);
    }

    public boolean isProcessed(String taskId, String recordKey) {
        return processedKeys.contains(taskId + ":" + recordKey);
    }

    public void markBatchCompleted(String taskId, long batchNumber) {
        Set<Long> batches = completedBatches.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet());
        batches.add(batchNumber);
        log.info("标记批次完成: taskId={}, batch={}", taskId, batchNumber);
    }

    public boolean isBatchCompleted(String taskId, long batchNumber) {
        Set<Long> batches = completedBatches.get(taskId);
        return batches != null && batches.contains(batchNumber);
    }

    public void recordProcessedWithCheckpoint(String taskId, String recordKey, 
                                                long position, String lastKey) {
        recordProcessed(taskId, recordKey);
        checkAndSaveCheckpoint(taskId, position, lastKey);
    }

    private void checkAndSaveCheckpoint(String taskId, long position, String lastKey) {
        ResumeConfig config = getTaskConfig(taskId);
        
        if (!config.isEnabled()) {
            return;
        }

        boolean shouldSaveCheckpoint = false;
        
        switch (config.getCheckpointType()) {
            case BY_RECORD_COUNT:
                AtomicLong counter = recordsSinceLastCheckpoint.get(taskId);
                if (counter != null) {
                    long count = counter.incrementAndGet();
                    if (count >= config.getCheckpointRecordInterval()) {
                        shouldSaveCheckpoint = true;
                        counter.set(0);
                    }
                }
                break;
                
            case BY_TIME:
                LocalDateTime lastTime = lastCheckpointTime.get(taskId);
                if (lastTime != null) {
                    LocalDateTime now = LocalDateTime.now();
                    java.time.Duration elapsed = java.time.Duration.between(lastTime, now);
                    if (elapsed.compareTo(config.getCheckpointTimeInterval()) >= 0) {
                        shouldSaveCheckpoint = true;
                        lastCheckpointTime.put(taskId, now);
                    }
                }
                break;
                
            case BY_BATCH:
            default:
                shouldSaveCheckpoint = true;
                break;
        }

        if (shouldSaveCheckpoint) {
            saveCheckpoint(taskId, position, lastKey);
        }
    }

    public void saveCheckpoint(String taskId, long position, String lastKey) {
        ResumeConfig config = getTaskConfig(taskId);
        
        if (!config.isEnabled()) {
            return;
        }

        taskResumePoints.put(taskId, position);
        
        if (config.isSaveProgressToDatabase()) {
            progressService.updatePosition(taskId, position, lastKey, true);
        }

        log.info("保存断点续传点: taskId={}, position={}, lastKey={}, type={}",
            taskId, position, lastKey, config.getCheckpointType());
    }

    public void recordBatchProgress(String taskId, long batchStart, long batchEnd,
                                      String lastBatchKey, long successCount, long failCount) {
        if (batchEnd > batchStart) {
            saveCheckpoint(taskId, batchEnd, lastBatchKey);
        }
    }

    public boolean validateResumePoint(String taskId, long expectedPosition, String expectedKey) {
        ResumeManager.ResumeState state = getResumeState(taskId);
        return state.isResumable() 
            && state.getResumePosition() == expectedPosition
            && expectedKey.equals(state.getLastProcessedKey());
    }

    public long getProcessedCount(String taskId) {
        return processedKeys.stream()
            .filter(key -> key.startsWith(taskId + ":"))
            .count();
    }

    public void clearTaskState(String taskId) {
        taskResumePoints.remove(taskId);
        taskConfigs.remove(taskId);
        recordsSinceLastCheckpoint.remove(taskId);
        lastCheckpointTime.remove(taskId);
        completedBatches.remove(taskId);
        processedKeys.removeIf(key -> key.startsWith(taskId + ":"));
        log.info("清理任务续传状态: taskId={}", taskId);
    }

    public CheckpointType getCheckpointType(String taskId) {
        return getTaskConfig(taskId).getCheckpointType();
    }

    public ResumeStrategy getResumeStrategy(String taskId) {
        return getTaskConfig(taskId).getResumeStrategy();
    }

    public void forceCheckpoint(String taskId, long position, String lastKey) {
        saveCheckpoint(taskId, position, lastKey);
    }
}
