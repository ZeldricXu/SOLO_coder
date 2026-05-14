package com.datamigrate.service;

import com.datamigrate.dto.ProgressResponse;
import com.datamigrate.entity.MigrateProgress;
import com.datamigrate.repository.MigrateProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressService {

    private final MigrateProgressRepository progressRepository;

    @Transactional
    public MigrateProgress createProgress(String taskId, long totalRecords) {
        MigrateProgress progress = new MigrateProgress();
        progress.setProgressId("progress_" + UUID.randomUUID().toString().substring(0, 8));
        progress.setTaskId(taskId);
        progress.setTotalRecords(totalRecords);
        progress.setMigratedRecords(0L);
        progress.setSuccessRecords(0L);
        progress.setFailRecords(0L);
        progress.setProgressRate(0);
        progress.setCurrentBatch(0);
        progress.setCurrentPosition(0L);
        progress.setIsResumable(false);
        return progressRepository.save(progress);
    }

    @Transactional
    public void updateProgress(String taskId, long migratedCount, long successCount, long failCount) {
        progressRepository.findByTaskId(taskId).ifPresent(progress -> {
            progress.setMigratedRecords(migratedCount);
            progress.setSuccessRecords(successCount);
            progress.setFailRecords(failCount);
            progressRepository.save(progress);
        });
    }

    @Transactional
    public void incrementProgress(String taskId, boolean success) {
        progressRepository.findByTaskId(taskId).ifPresent(progress -> {
            progress.setMigratedRecords(progress.getMigratedRecords() + 1);
            if (success) {
                progress.setSuccessRecords(progress.getSuccessRecords() + 1);
            } else {
                progress.setFailRecords(progress.getFailRecords() + 1);
            }
            progressRepository.save(progress);
        });
    }

    @Transactional
    public void incrementBatch(String taskId, long batchMigrated, long batchSuccess, long batchFail) {
        progressRepository.findByTaskId(taskId).ifPresent(progress -> {
            progress.setMigratedRecords(progress.getMigratedRecords() + batchMigrated);
            progress.setSuccessRecords(progress.getSuccessRecords() + batchSuccess);
            progress.setFailRecords(progress.getFailRecords() + batchFail);
            progress.setCurrentBatch(progress.getCurrentBatch() + 1);
            progressRepository.save(progress);
        });
    }

    @Transactional
    public void updatePosition(String taskId, long position, String lastKey, boolean resumable) {
        progressRepository.findByTaskId(taskId).ifPresent(progress -> {
            progress.setCurrentPosition(position);
            progress.setLastProcessedKey(lastKey);
            progress.setIsResumable(resumable);
            progressRepository.save(progress);
        });
    }

    public Optional<MigrateProgress> getProgress(String taskId) {
        return progressRepository.findByTaskId(taskId);
    }

    public ProgressResponse getProgressResponse(String taskId) {
        Optional<MigrateProgress> progressOpt = getProgress(taskId);
        ProgressResponse.ProgressInfo info = new ProgressResponse.ProgressInfo();
        if (progressOpt.isPresent()) {
            MigrateProgress p = progressOpt.get();
            info.setTotalRecords(p.getTotalRecords());
            info.setMigratedRecords(p.getMigratedRecords());
            info.setSuccessRecords(p.getSuccessRecords());
            info.setFailRecords(p.getFailRecords());
            info.setProgressRate(p.getProgressRate());
            info.setCurrentBatch(p.getCurrentBatch());
        }
        return new ProgressResponse(info);
    }

    @Transactional
    public void resetProgress(String taskId) {
        progressRepository.findByTaskId(taskId).ifPresent(progress -> {
            progress.setMigratedRecords(0L);
            progress.setSuccessRecords(0L);
            progress.setFailRecords(0L);
            progress.setProgressRate(0);
            progress.setCurrentBatch(0);
            progress.setCurrentPosition(0L);
            progress.setLastProcessedKey(null);
            progressRepository.save(progress);
        });
    }
}
