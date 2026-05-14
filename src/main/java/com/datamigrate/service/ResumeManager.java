package com.datamigrate.service;

import com.datamigrate.entity.MigrateProgress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeManager {

    private final ProgressService progressService;
    
    private final Set<String> processedKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> taskResumePoints = new ConcurrentHashMap<>();

    public static class ResumeState {
        private final boolean resumable;
        private final long resumePosition;
        private final String lastProcessedKey;
        private final long migratedRecords;
        private final long successRecords;
        private final long failRecords;

        public ResumeState(boolean resumable, long resumePosition, String lastProcessedKey,
                            long migratedRecords, long successRecords, long failRecords) {
            this.resumable = resumable;
            this.resumePosition = resumePosition;
            this.lastProcessedKey = lastProcessedKey;
            this.migratedRecords = migratedRecords;
            this.successRecords = successRecords;
            this.failRecords = failRecords;
        }

        public boolean isResumable() { return resumable; }
        public long getResumePosition() { return resumePosition; }
        public String getLastProcessedKey() { return lastProcessedKey; }
        public long getMigratedRecords() { return migratedRecords; }
        public long getSuccessRecords() { return successRecords; }
        public long getFailRecords() { return failRecords; }
    }

    public ResumeState getResumeState(String taskId) {
        Optional<MigrateProgress> progressOpt = progressService.getProgress(taskId);
        
        if (progressOpt.isPresent()) {
            MigrateProgress progress = progressOpt.get();
            if (Boolean.TRUE.equals(progress.getIsResumable()) && progress.getCurrentPosition() > 0) {
                log.info("找到断点续传点: taskId={}, position={}, lastKey={}",
                    taskId, progress.getCurrentPosition(), progress.getLastProcessedKey());
                return new ResumeState(
                    true,
                    progress.getCurrentPosition(),
                    progress.getLastProcessedKey(),
                    progress.getMigratedRecords(),
                    progress.getSuccessRecords(),
                    progress.getFailRecords()
                );
            }
        }
        
        return new ResumeState(false, 0, null, 0, 0, 0);
    }

    public boolean markProcessed(String taskId, String recordKey) {
        String compositeKey = taskId + ":" + recordKey;
        return processedKeys.add(compositeKey);
    }

    public boolean isProcessed(String taskId, String recordKey) {
        String compositeKey = taskId + ":" + recordKey;
        return processedKeys.contains(compositeKey);
    }

    public void setResumePoint(String taskId, long position, String lastKey) {
        taskResumePoints.put(taskId, position);
        progressService.updatePosition(taskId, position, lastKey, true);
        log.info("设置断点续传点: taskId={}, position={}, lastKey={}", taskId, position, lastKey);
    }

    public void clearTaskState(String taskId) {
        taskResumePoints.remove(taskId);
        processedKeys.removeIf(key -> key.startsWith(taskId + ":"));
        log.info("清理任务状态: taskId={}", taskId);
    }

    public boolean validateResumePoint(String taskId, long expectedPosition, String expectedKey) {
        ResumeState state = getResumeState(taskId);
        return state.isResumable() 
            && state.getResumePosition() == expectedPosition
            && expectedKey.equals(state.getLastProcessedKey());
    }

    public long getProcessedCount(String taskId) {
        return processedKeys.stream()
            .filter(key -> key.startsWith(taskId + ":"))
            .count();
    }

    public void registerBatchProgress(String taskId, long batchStart, long batchEnd, 
                                       String lastBatchKey, long successCount, long failCount) {
        if (batchEnd > batchStart) {
            setResumePoint(taskId, batchEnd, lastBatchKey);
        }
    }
}
