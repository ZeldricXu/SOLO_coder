package com.projectcollab.service.reminder;

import com.projectcollab.entity.Project;
import com.projectcollab.entity.Reminder;
import com.projectcollab.entity.Stage;
import com.projectcollab.entity.Task;
import com.projectcollab.repository.ReminderRepository;
import com.projectcollab.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReminderService {

    @Autowired
    private ReminderRepository reminderRepository;

    public List<Reminder> getRemindersByProjectId(String projectId) {
        return reminderRepository.findByProject_ProjectId(projectId);
    }

    public List<Reminder> getPendingReminders() {
        return reminderRepository.findByReminderStatus("pending");
    }

    @Transactional
    public Reminder createDeadlineReminder(Task task) {
        if (task.getTaskDeadline() == null) {
            return null;
        }

        Reminder reminder = new Reminder();
        reminder.setReminderId(IdGenerator.generateReminderId());
        reminder.setProject(task.getProject());
        reminder.setReminderType("deadline");
        reminder.setReminderContent("任务截止提醒: " + task.getTaskName() + "，截止时间: " + task.getTaskDeadline());
        reminder.setReminderTime(LocalDateTime.from(task.getTaskDeadline().atStartOfDay()).minusDays(1));
        reminder.setReminderStatus("pending");
        reminder.setTaskId(task.getTaskId());
        reminder.setUserId(task.getTaskAssignee());
        reminder.setCreatedAt(LocalDateTime.now());

        return reminderRepository.save(reminder);
    }

    @Transactional
    public Reminder createDelayWarning(Project project) {
        Reminder reminder = new Reminder();
        reminder.setReminderId(IdGenerator.generateReminderId());
        reminder.setProject(project);
        reminder.setReminderType("delay_warning");
        reminder.setReminderContent("项目延期预警: " + project.getProjectName() + " 当前进度: " + project.getProjectProgress() + "%");
        reminder.setReminderTime(LocalDateTime.now());
        reminder.setReminderStatus("pending");
        reminder.setCreatedAt(LocalDateTime.now());

        return reminderRepository.save(reminder);
    }

    @Transactional
    public Reminder createProgressWarning(Project project, Stage stage, Task task) {
        Reminder reminder = new Reminder();
        reminder.setReminderId(IdGenerator.generateReminderId());
        reminder.setProject(project);
        reminder.setReminderType("progress_warning");
        reminder.setReminderContent("阶段进度警告: 阶段 " + stage.getStageName() + " 进度为 " + 
                stage.getStageProgress() + "%，低于警告阈值 " + stage.getProgressWarningThreshold() + "%");
        reminder.setReminderTime(LocalDateTime.now());
        reminder.setReminderStatus("sent");
        reminder.setTaskId(task.getTaskId());
        reminder.setCreatedAt(LocalDateTime.now());

        return reminderRepository.save(reminder);
    }

    @Transactional
    public Reminder createProgressCriticalWarning(Project project, Stage stage, Task task) {
        Reminder reminder = new Reminder();
        reminder.setReminderId(IdGenerator.generateReminderId());
        reminder.setProject(project);
        reminder.setReminderType("progress_critical");
        reminder.setReminderContent("阶段进度严重警告: 阶段 " + stage.getStageName() + " 进度为 " + 
                stage.getStageProgress() + "%，低于严重阈值 " + stage.getProgressCriticalThreshold() + "%");
        reminder.setReminderTime(LocalDateTime.now());
        reminder.setReminderStatus("sent");
        reminder.setTaskId(task.getTaskId());
        reminder.setCreatedAt(LocalDateTime.now());

        return reminderRepository.save(reminder);
    }

    @Transactional
    public Reminder createTaskCompletionNotification(Task task) {
        Reminder reminder = new Reminder();
        reminder.setReminderId(IdGenerator.generateReminderId());
        reminder.setProject(task.getProject());
        reminder.setReminderType("task_completed");
        reminder.setReminderContent("任务完成通知: " + task.getTaskName());
        reminder.setReminderTime(LocalDateTime.now());
        reminder.setReminderStatus("sent");
        reminder.setTaskId(task.getTaskId());
        reminder.setCreatedAt(LocalDateTime.now());

        return reminderRepository.save(reminder);
    }

    @Transactional
    public Reminder createTaskAssignmentNotification(Task task) {
        Reminder reminder = new Reminder();
        reminder.setReminderId(IdGenerator.generateReminderId());
        reminder.setProject(task.getProject());
        reminder.setReminderType("task_assigned");
        reminder.setReminderContent("任务分配通知: 您被分配了任务 - " + task.getTaskName());
        reminder.setReminderTime(LocalDateTime.now());
        reminder.setReminderStatus("sent");
        reminder.setTaskId(task.getTaskId());
        reminder.setUserId(task.getTaskAssignee());
        reminder.setCreatedAt(LocalDateTime.now());

        return reminderRepository.save(reminder);
    }

    @Transactional
    public Reminder createDocumentShareNotification(Project project, String docName, String userId) {
        Reminder reminder = new Reminder();
        reminder.setReminderId(IdGenerator.generateReminderId());
        reminder.setProject(project);
        reminder.setReminderType("document_shared");
        reminder.setReminderContent("文档共享通知: 文档 " + docName + " 已共享给您");
        reminder.setReminderTime(LocalDateTime.now());
        reminder.setReminderStatus("sent");
        reminder.setUserId(userId);
        reminder.setCreatedAt(LocalDateTime.now());

        return reminderRepository.save(reminder);
    }

    @Transactional
    public void markReminderAsSent(String reminderId) {
        reminderRepository.findById(reminderId).ifPresent(reminder -> {
            reminder.setReminderStatus("sent");
            reminderRepository.save(reminder);
        });
    }
}
