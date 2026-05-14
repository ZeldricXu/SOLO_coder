package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.model.ProjectActivity;
import com.projmanage.repository.ProjectActivityRepository;
import com.projmanage.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class ProjectActivityService {

    private final ProjectActivityRepository projectActivityRepository;

    public ProjectActivityService(ProjectActivityRepository projectActivityRepository) {
        this.projectActivityRepository = projectActivityRepository;
    }

    @Transactional
    public ProjectActivity recordActivity(String projectId) {
        Optional<ProjectActivity> existingOpt = projectActivityRepository.findByProjectId(projectId);

        ProjectActivity activity;
        if (existingOpt.isPresent()) {
            activity = existingOpt.get();
            activity.setUpdateCount(activity.getUpdateCount() + 1);
            activity.setLastActivityTime(LocalDateTime.now());
        } else {
            activity = new ProjectActivity();
            activity.setActivityId(IdGenerator.generateProjectId());
            activity.setProjectId(projectId);
            activity.setUpdateCount(1);
            activity.setLastActivityTime(LocalDateTime.now());
            activity.setActivityLevel(Constants.ACTIVITY_LEVEL_LOW);
            activity.setStatFrequencyMinutes(Constants.STAT_FREQUENCY_LOW_MINUTES);
            activity.setCreatedAt(LocalDateTime.now());
        }

        updateActivityLevelAndFrequency(activity);
        activity.setUpdatedAt(LocalDateTime.now());

        return projectActivityRepository.save(activity);
    }

    @Transactional
    public void updateActivityLevelAndFrequency(ProjectActivity activity) {
        int updateCount = activity.getUpdateCount();
        LocalDateTime lastActivityTime = activity.getLastActivityTime();
        LocalDateTime now = LocalDateTime.now();

        long hoursSinceLastActivity = java.time.Duration.between(
                lastActivityTime != null ? lastActivityTime : now,
                now
        ).toHours();

        String activityLevel;
        int statFrequencyMinutes;

        if (hoursSinceLastActivity > 24) {
            activityLevel = Constants.ACTIVITY_LEVEL_INACTIVE;
            statFrequencyMinutes = Constants.STAT_FREQUENCY_INACTIVE_MINUTES;
        } else if (updateCount >= Constants.HIGH_ACTIVITY_THRESHOLD) {
            activityLevel = Constants.ACTIVITY_LEVEL_HIGH;
            statFrequencyMinutes = Constants.STAT_FREQUENCY_HIGH_MINUTES;
        } else if (updateCount >= Constants.MEDIUM_ACTIVITY_THRESHOLD) {
            activityLevel = Constants.ACTIVITY_LEVEL_MEDIUM;
            statFrequencyMinutes = Constants.STAT_FREQUENCY_MEDIUM_MINUTES;
        } else if (updateCount >= Constants.LOW_ACTIVITY_THRESHOLD) {
            activityLevel = Constants.ACTIVITY_LEVEL_LOW;
            statFrequencyMinutes = Constants.STAT_FREQUENCY_LOW_MINUTES;
        } else {
            activityLevel = Constants.ACTIVITY_LEVEL_LOW;
            statFrequencyMinutes = Constants.STAT_FREQUENCY_LOW_MINUTES;
        }

        activity.setActivityLevel(activityLevel);
        activity.setStatFrequencyMinutes(statFrequencyMinutes);
    }

    public Optional<ProjectActivity> getProjectActivity(String projectId) {
        return projectActivityRepository.findByProjectId(projectId);
    }

    @Transactional
    public void resetUpdateCount(String projectId) {
        Optional<ProjectActivity> existingOpt = projectActivityRepository.findByProjectId(projectId);
        if (existingOpt.isPresent()) {
            ProjectActivity activity = existingOpt.get();
            activity.setUpdateCount(0);
            activity.setUpdatedAt(LocalDateTime.now());
            projectActivityRepository.save(activity);
        }
    }

    @Transactional
    public void updateLastStatTime(String projectId) {
        Optional<ProjectActivity> existingOpt = projectActivityRepository.findByProjectId(projectId);
        if (existingOpt.isPresent()) {
            ProjectActivity activity = existingOpt.get();
            activity.setLastStatTime(LocalDateTime.now());
            activity.setUpdatedAt(LocalDateTime.now());
            projectActivityRepository.save(activity);
        }
    }

    public boolean shouldUpdateProgress(String projectId) {
        Optional<ProjectActivity> existingOpt = projectActivityRepository.findByProjectId(projectId);

        if (!existingOpt.isPresent()) {
            return true;
        }

        ProjectActivity activity = existingOpt.get();
        LocalDateTime lastStatTime = activity.getLastStatTime();
        Integer statFrequencyMinutes = activity.getStatFrequencyMinutes();

        if (lastStatTime == null) {
            return true;
        }

        long minutesSinceLastStat = java.time.Duration.between(lastStatTime, LocalDateTime.now()).toMinutes();
        return minutesSinceLastStat >= statFrequencyMinutes;
    }

    public String getProjectActivityLevel(String projectId) {
        Optional<ProjectActivity> existingOpt = projectActivityRepository.findByProjectId(projectId);
        return existingOpt.map(ProjectActivity::getActivityLevel).orElse(Constants.ACTIVITY_LEVEL_LOW);
    }
}
