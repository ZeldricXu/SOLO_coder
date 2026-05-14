
package com.learningplatform.service;

import com.learningplatform.config.BackupConfig;
import com.learningplatform.entity.ChapterProgress;
import com.learningplatform.entity.Progress;
import com.learningplatform.entity.ProgressBackup;
import com.learningplatform.exception.BusinessException;
import com.learningplatform.repository.ChapterProgressRepository;
import com.learningplatform.repository.ProgressBackupRepository;
import com.learningplatform.repository.ProgressRepository;
import com.learningplatform.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ProgressBackupService {

    private static final Logger logger = LoggerFactory.getLogger(ProgressBackupService.class);

    @Autowired
    private ProgressBackupRepository progressBackupRepository;

    @Autowired
    private ProgressRepository progressRepository;

    @Autowired
    private ChapterProgressRepository chapterProgressRepository;

    @Autowired
    private BackupConfig backupConfig;

    private final Map<String, AtomicInteger> activityCounter = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastActivityTime = new ConcurrentHashMap<>();

    @Transactional
    public ProgressBackup createBackup(String progressId, String backupReason) {
        Progress progress = progressRepository.findById(progressId)
                .orElseThrow(() -> new BusinessException(404, "进度不存在: " + progressId));

        String backupLevel = determineBackupLevel(progressId);
        int activityLevel = getActivityLevel(progressId);

        ProgressBackup backup = new ProgressBackup();
        backup.setBackupId(IdGenerator.generateId("backup"));
        backup.setProgressId(progress.getProgressId());
        backup.setCourseId(progress.getCourseId());
        backup.setStudentId(progress.getStudentId());
        backup.setProgressStatus(progress.getProgressStatus());
        backup.setProgressPercent(progress.getProgressPercent());
        backup.setChaptersCompleted(progress.getChaptersCompleted());
        backup.setTotalChapters(progress.getTotalChapters());
        backup.setLearningTime(progress.getLearningTime());
        backup.setBackupReason(backupReason);
        backup.setBackupLevel(backupLevel);
        backup.setIsVerified(false);
        backup.setActivityLevel(activityLevel);

        ProgressBackup saved = progressBackupRepository.save(backup);
        logger.info("创建进度备份: progress={}, backup={}, reason={}, level={}, activity={}",
                progressId, saved.getBackupId(), backupReason, backupLevel, activityLevel);

        incrementActivity(progressId);
        return saved;
    }

    public String determineBackupLevel(String progressId) {
        int activity = getActivityLevel(progressId);
        return backupConfig.determineBackupLevel(activity);
    }

    private void incrementActivity(String progressId) {
        activityCounter.computeIfAbsent(progressId, k -> new AtomicInteger(0)).incrementAndGet();
        lastActivityTime.put(progressId, LocalDateTime.now());
        logger.debug("增加活跃度计数器: progress={}, currentLevel={}", progressId, getActivityLevel(progressId));
    }

    public Duration getBackupInterval(String progressId) {
        String level = determineBackupLevel(progressId);
        Duration interval = backupConfig.getFrequency().getIntervalByLevel(level);
        logger.debug("获取备份间隔: progress={}, level={}, interval={}min", 
                progressId, level, interval.toMinutes());
        return interval;
    }

    public int getBackupFrequency(String progressId) {
        String level = determineBackupLevel(progressId);
        switch (level) {
            case "high":
                return backupConfig.getFrequency().getHighIntervalMinutes();
            case "medium":
                return backupConfig.getFrequency().getMediumIntervalMinutes();
            default:
                return backupConfig.getFrequency().getLowIntervalMinutes();
        }
    }

    public void resetActivityCounter(String progressId) {
        activityCounter.remove(progressId);
        lastActivityTime.remove(progressId);
        logger.debug("重置活跃度计数器: progress={}", progressId);
    }

    public int getActivityLevel(String progressId) {
        return activityCounter.getOrDefault(progressId, new AtomicInteger(0)).get();
    }

    public void setActivityLevel(String progressId, int level) {
        activityCounter.put(progressId, new AtomicInteger(level));
        if (level > 0) {
            lastActivityTime.put(progressId, LocalDateTime.now());
        }
        logger.debug("设置活跃度: progress={}, level={}", progressId, level);
    }

    public void recordLearningActivity(String progressId) {
        incrementActivity(progressId);
    }

    public int getHighActivityThreshold() {
        return backupConfig.getActivity().getHighThreshold();
    }

    public int getMediumActivityThreshold() {
        return backupConfig.getActivity().getMediumThreshold();
    }

    public int getLowActivityThreshold() {
        return backupConfig.getActivity().getLowThreshold();
    }

    public Duration getActivityWindow() {
        return backupConfig.getActivityWindow();
    }

    @Scheduled(fixedRate = 60000)
    public void cleanupInactiveActivityCounters() {
        LocalDateTime cutoff = LocalDateTime.now().minus(backupConfig.getActivityWindow());
        List<String> toRemove = new ArrayList<>();
        
        for (Map.Entry<String, LocalDateTime> entry : lastActivityTime.entrySet()) {
            if (entry.getValue().isBefore(cutoff)) {
                toRemove.add(entry.getKey());
            }
        }
        
        for (String progressId : toRemove) {
            resetActivityCounter(progressId);
        }
        
        if (!toRemove.isEmpty()) {
            logger.info("清理不活跃的活跃度计数器: count={}", toRemove.size());
        }
    }

    @Transactional
    public boolean verifyBackup(String backupId) {
        ProgressBackup backup = progressBackupRepository.findById(backupId)
                .orElseThrow(() -> new BusinessException(404, "备份不存在: " + backupId));

        Optional<Progress> progressOpt = progressRepository.findById(backup.getProgressId());
        if (progressOpt.isEmpty()) {
            backup.setIsVerified(false);
            progressBackupRepository.save(backup);
            return false;
        }

        Progress progress = progressOpt.get();
        boolean verified = 
            progress.getCourseId().equals(backup.getCourseId()) &&
            progress.getStudentId().equals(backup.getStudentId()) &&
            progress.getProgressPercent().equals(backup.getProgressPercent()) &&
            progress.getChaptersCompleted().equals(backup.getChaptersCompleted()) &&
            progress.getTotalChapters().equals(backup.getTotalChapters());

        backup.setIsVerified(verified);
        progressBackupRepository.save(backup);
        
        logger.info("备份完整性校验: backup={}, verified={}", backupId, verified);
        return verified;
    }

    @Transactional
    public List<ProgressBackup> verifyAllBackups(String progressId) {
        List<ProgressBackup> backups = progressBackupRepository.findByProgressIdOrderByBackupTimeDesc(progressId);
        for (ProgressBackup backup : backups) {
            verifyBackup(backup.getBackupId());
        }
        return progressBackupRepository.findByProgressIdOrderByBackupTimeDesc(progressId);
    }

    @Transactional
    @CacheEvict(value = "progress_backup", key = "#backupId")
    public Progress restoreFromBackup(String backupId) {
        ProgressBackup backup = progressBackupRepository.findById(backupId)
                .orElseThrow(() -> new BusinessException(404, "备份不存在: " + backupId));

        Progress progress = progressRepository.findById(backup.getProgressId()).orElse(null);
        if (progress == null) {
            progress = new Progress();
            progress.setProgressId(backup.getProgressId());
        }

        progress.setCourseId(backup.getCourseId());
        progress.setStudentId(backup.getStudentId());
        progress.setProgressStatus(backup.getProgressStatus());
        progress.setProgressPercent(backup.getProgressPercent());
        progress.setChaptersCompleted(backup.getChaptersCompleted());
        progress.setTotalChapters(backup.getTotalChapters());
        progress.setLearningTime(backup.getLearningTime());

        Progress restored = progressRepository.save(progress);
        logger.info("从备份恢复进度: backup={}, progress={}", backupId, restored.getProgressId());
        
        return restored;
    }

    @Cacheable(value = "progress_backup", key = "#progressId + ':latest'")
    public Optional<ProgressBackup> getLatestBackup(String progressId) {
        return progressBackupRepository.findFirstByProgressIdOrderByBackupTimeDesc(progressId);
    }

    @Cacheable(value = "progress_backup", key = "#progressId + ':all'")
    public List<ProgressBackup> getBackupsByProgress(String progressId) {
        return progressBackupRepository.findByProgressIdOrderByBackupTimeDesc(progressId);
    }

    public List<ProgressBackup> getBackupsByStudent(String studentId) {
        return progressBackupRepository.findByStudentIdOrderByBackupTimeDesc(studentId);
    }

    public long getBackupCount(String progressId) {
        return progressBackupRepository.countByProgressId(progressId);
    }

    @Transactional
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledCleanupOldBackups() {
        cleanupOldBackups(backupConfig.getCleanupDaysToKeep());
    }

    @Transactional
    public void cleanupOldBackups(int daysToKeep) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysToKeep);
        progressBackupRepository.deleteByBackupTimeBefore(cutoff);
        logger.info("清理旧备份: cutoff={}, daysToKeep={}", cutoff, daysToKeep);
    }

    public boolean shouldBackup(String progressId) {
        Optional<ProgressBackup> latestBackup = getLatestBackup(progressId);
        if (latestBackup.isEmpty()) {
            return true;
        }
        
        Duration interval = getBackupInterval(progressId);
        LocalDateTime lastBackupTime = latestBackup.get().getBackupTime();
        LocalDateTime threshold = LocalDateTime.now().minus(interval);
        
        boolean shouldBackup = lastBackupTime.isBefore(threshold);
        logger.debug("备份决策: progress={}, shouldBackup={}, interval={}min, lastBackup={}",
                progressId, shouldBackup, interval.toMinutes(), lastBackupTime);
        
        return shouldBackup;
    }

    @Transactional
    public List<ProgressBackup> batchBackup(List<String> progressIds, String reason) {
        List<ProgressBackup> backups = new ArrayList<>();
        for (String progressId : progressIds) {
            if (shouldBackup(progressId)) {
                backups.add(createBackup(progressId, reason));
            }
        }
        logger.info("批量备份完成: count={}, reason={}", backups.size(), reason);
        return backups;
    }
}
