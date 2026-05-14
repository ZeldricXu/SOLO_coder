package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.dto.ProgressResponse;
import com.projmanage.model.Progress;
import com.projmanage.model.Task;
import com.projmanage.repository.ProgressRepository;
import com.projmanage.repository.TaskRepository;
import com.projmanage.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ProgressService {

    private static final Logger logger = LoggerFactory.getLogger(ProgressService.class);

    private final ProgressRepository progressRepository;
    private final TaskRepository taskRepository;
    private final ProjectActivityService projectActivityService;

    public ProgressService(ProgressRepository progressRepository,
                           TaskRepository taskRepository,
                           ProjectActivityService projectActivityService) {
        this.progressRepository = progressRepository;
        this.taskRepository = taskRepository;
        this.projectActivityService = projectActivityService;
    }

    @Transactional
    public void updateProgress(String projectId) {
        if (!projectActivityService.shouldUpdateProgress(projectId)) {
            logger.debug("项目 {} 暂未到统计时间，跳过进度更新", projectId);
            return;
        }

        doUpdateProgress(projectId);
        projectActivityService.updateLastStatTime(projectId);
        logger.info("项目 {} 进度统计完成", projectId);
    }

    @Transactional
    public void forceUpdateProgress(String projectId) {
        logger.info("强制更新项目 {} 进度统计", projectId);
        doUpdateProgress(projectId);
        projectActivityService.updateLastStatTime(projectId);
    }

    private void doUpdateProgress(String projectId) {
        List<Task> tasks = taskRepository.findByProjectId(projectId);

        int totalTasks = tasks.size();
        int completedTasks = 0;
        int inProgressTasks = 0;
        int pendingTasks = 0;
        int totalProgress = 0;

        for (Task task : tasks) {
            totalProgress += task.getProgress() != null ? task.getProgress() : 0;

            if (Constants.TASK_STATUS_COMPLETED.equals(task.getTaskStatus())) {
                completedTasks++;
            } else if (Constants.TASK_STATUS_IN_PROGRESS.equals(task.getTaskStatus())) {
                inProgressTasks++;
            } else {
                pendingTasks++;
            }
        }

        int overallProgress = totalTasks > 0 ? totalProgress / totalTasks : 0;

        Optional<Progress> existingProgress = progressRepository.findByProjectId(projectId);

        Progress progress;
        if (existingProgress.isPresent()) {
            progress = existingProgress.get();
        } else {
            progress = new Progress();
            progress.setProgressId(IdGenerator.generateProgressId());
            progress.setProjectId(projectId);
        }

        progress.setTotalTasks(totalTasks);
        progress.setCompletedTasks(completedTasks);
        progress.setInProgressTasks(inProgressTasks);
        progress.setPendingTasks(pendingTasks);
        progress.setOverallProgress(overallProgress);
        progress.setUpdatedAt(LocalDateTime.now());

        progressRepository.save(progress);
    }

    public ProgressResponse getProjectProgress(String projectId) {
        forceUpdateProgress(projectId);

        Optional<Progress> progressOpt = progressRepository.findByProjectId(projectId);

        ProgressResponse response = new ProgressResponse();
        if (progressOpt.isPresent()) {
            Progress progress = progressOpt.get();
            response.setOverallProgress(progress.getOverallProgress());
            response.setCompletedTasks(progress.getCompletedTasks());
            response.setTotalTasks(progress.getTotalTasks());
            response.setInProgressTasks(progress.getInProgressTasks());
            response.setPendingTasks(progress.getPendingTasks());
        } else {
            response.setOverallProgress(0);
            response.setCompletedTasks(0);
            response.setTotalTasks(0);
            response.setInProgressTasks(0);
            response.setPendingTasks(0);
        }

        return response;
    }

    public String getProjectActivityLevel(String projectId) {
        return projectActivityService.getProjectActivityLevel(projectId);
    }

    public int getProjectStatFrequency(String projectId) {
        return projectActivityService.getProjectActivity(projectId)
                .map(pa -> pa.getStatFrequencyMinutes() != null ? pa.getStatFrequencyMinutes() : Constants.STAT_FREQUENCY_LOW_MINUTES)
                .orElse(Constants.STAT_FREQUENCY_LOW_MINUTES);
    }
}
