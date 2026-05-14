package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.model.Milestone;
import com.projmanage.model.MilestoneReminderConfig;
import com.projmanage.model.Task;
import com.projmanage.repository.MilestoneRepository;
import com.projmanage.repository.TaskRepository;
import com.projmanage.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class MilestoneService {

    private final MilestoneRepository milestoneRepository;
    private final TaskRepository taskRepository;
    private final CollaborationService collaborationService;
    private final MilestoneReminderConfigService reminderConfigService;

    public MilestoneService(MilestoneRepository milestoneRepository,
                            TaskRepository taskRepository,
                            CollaborationService collaborationService,
                            MilestoneReminderConfigService reminderConfigService) {
        this.milestoneRepository = milestoneRepository;
        this.taskRepository = taskRepository;
        this.collaborationService = collaborationService;
        this.reminderConfigService = reminderConfigService;
    }

    @Transactional
    public Milestone createMilestone(String projectId, String milestoneName, LocalDate milestoneDate) {
        Milestone milestone = new Milestone();
        milestone.setMilestoneId(IdGenerator.generateMilestoneId());
        milestone.setProjectId(projectId);
        milestone.setMilestoneName(milestoneName);
        milestone.setMilestoneDate(milestoneDate);
        milestone.setStatus(Constants.MILESTONE_STATUS_PENDING);
        milestone.setProgress(0);

        Milestone savedMilestone = milestoneRepository.save(milestone);

        reminderConfigService.createDefaultConfig(projectId, savedMilestone.getMilestoneId());

        return savedMilestone;
    }

    public Optional<Milestone> getMilestoneById(String milestoneId) {
        return milestoneRepository.findById(milestoneId);
    }

    public List<Milestone> getMilestonesByProject(String projectId) {
        return milestoneRepository.findByProjectId(projectId);
    }

    @Transactional
    public void updateMilestoneProgress(String milestoneId) {
        Optional<Milestone> milestoneOpt = milestoneRepository.findById(milestoneId);
        if (!milestoneOpt.isPresent()) {
            return;
        }

        Milestone milestone = milestoneOpt.get();
        List<Task> tasks = taskRepository.findByMilestoneId(milestoneId);

        if (tasks.isEmpty()) {
            milestone.setProgress(0);
            milestone.setStatus(Constants.MILESTONE_STATUS_PENDING);
        } else {
            int totalProgress = 0;
            int completedCount = 0;
            int inProgressCount = 0;

            for (Task task : tasks) {
                totalProgress += task.getProgress() != null ? task.getProgress() : 0;
                if (Constants.TASK_STATUS_COMPLETED.equals(task.getTaskStatus())) {
                    completedCount++;
                } else if (Constants.TASK_STATUS_IN_PROGRESS.equals(task.getTaskStatus())) {
                    inProgressCount++;
                }
            }

            int avgProgress = totalProgress / tasks.size();
            milestone.setProgress(avgProgress);

            if (completedCount == tasks.size()) {
                milestone.setStatus(Constants.MILESTONE_STATUS_COMPLETED);
                reminderConfigService.resetReminderCount(milestoneId);
            } else if (inProgressCount > 0 || completedCount > 0) {
                milestone.setStatus(Constants.MILESTONE_STATUS_IN_PROGRESS);
            } else {
                milestone.setStatus(Constants.MILESTONE_STATUS_PENDING);
            }
        }

        milestoneRepository.save(milestone);

        checkMilestoneReminder(milestone);
    }

    private void checkMilestoneReminder(Milestone milestone) {
        if (milestone.getMilestoneDate() == null) {
            return;
        }

        if (Constants.MILESTONE_STATUS_COMPLETED.equals(milestone.getStatus())) {
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate milestoneDate = milestone.getMilestoneDate();
        long daysUntil = today.until(milestoneDate, java.time.temporal.ChronoUnit.DAYS);

        if (reminderConfigService.shouldSendReminder(milestone.getMilestoneId(), daysUntil)) {
            Optional<MilestoneReminderConfig> configOpt = reminderConfigService.getConfigByMilestoneId(milestone.getMilestoneId());

            if (configOpt.isPresent()) {
                MilestoneReminderConfig config = configOpt.get();
                if (config.getLastReminderTime() != null && config.getEnableMultipleReminders()) {
                    long hoursSinceLastReminder = java.time.Duration.between(
                            config.getLastReminderTime(),
                            LocalDateTime.now()
                    ).toHours();

                    if (hoursSinceLastReminder < config.getReminderIntervalHours()) {
                        return;
                    }
                }
            }

            String reminderMessage;
            if (daysUntil == 0) {
                reminderMessage = "里程碑 [" + milestone.getMilestoneName() + "] 今天到期";
            } else if (daysUntil > 0) {
                reminderMessage = "里程碑 [" + milestone.getMilestoneName() + "] 将于 " + daysUntil + " 天后到达";
            } else {
                reminderMessage = "里程碑 [" + milestone.getMilestoneName() + "] 已延期 " + Math.abs(daysUntil) + " 天";
            }

            collaborationService.sendNotification(
                    "system",
                    milestone.getProjectId(),
                    null,
                    Constants.NOTIFICATION_TYPE_MILESTONE_REMINDER,
                    "里程碑提醒",
                    reminderMessage
            );

            reminderConfigService.incrementReminderCount(milestone.getMilestoneId());
        }
    }

    @Transactional
    public void assignTaskToMilestone(String milestoneId, String taskId) {
        Optional<Milestone> milestoneOpt = milestoneRepository.findById(milestoneId);
        Optional<Task> taskOpt = taskRepository.findById(taskId);

        if (!milestoneOpt.isPresent() || !taskOpt.isPresent()) {
            return;
        }

        Milestone milestone = milestoneOpt.get();
        Task task = taskOpt.get();

        if (!milestone.getMilestoneTasks().contains(taskId)) {
            milestone.getMilestoneTasks().add(taskId);
        }
        task.setMilestoneId(milestoneId);

        milestoneRepository.save(milestone);
        taskRepository.save(task);

        updateMilestoneProgress(milestoneId);
    }
}
