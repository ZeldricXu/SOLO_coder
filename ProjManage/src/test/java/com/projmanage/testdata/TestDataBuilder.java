package com.projmanage.testdata;

import com.projmanage.config.Constants;
import com.projmanage.model.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestDataBuilder {

    public static final String DEFAULT_PROJECT_ID = "project_001";
    public static final String DEFAULT_TASK_ID = "task_001";
    public static final String DEFAULT_MILESTONE_ID = "milestone_001";
    public static final String DEFAULT_USER_ID = "user_001";
    public static final String OWNER_ID = "user_manager_01";

    private TestDataBuilder() {
    }

    public static ProjectBuilder aProject() {
        return new ProjectBuilder();
    }

    public static TaskBuilder aTask() {
        return new TaskBuilder();
    }

    public static MilestoneBuilder aMilestone() {
        return new MilestoneBuilder();
    }

    public static RiskBuilder aRisk() {
        return new RiskBuilder();
    }

    public static ProgressBuilder aProgress() {
        return new ProgressBuilder();
    }

    public static StatisticBuilder aStatistic() {
        return new StatisticBuilder();
    }

    public static NotificationBuilder aNotification() {
        return new NotificationBuilder();
    }

    public static class ProjectBuilder {
        private String projectId = DEFAULT_PROJECT_ID;
        private String projectName = "产品研发项目";
        private String projectType = "development";
        private String projectOwner = OWNER_ID;
        private List<String> projectMembers = new ArrayList<>(Arrays.asList("user_dev_01", "user_dev_02"));
        private LocalDate startDate = LocalDate.now().minusDays(10);
        private LocalDate endDate = LocalDate.now().plusDays(30);
        private String projectStatus = Constants.PROJECT_STATUS_IN_PROGRESS;
        private LocalDateTime createdAt = LocalDateTime.now().minusDays(10);

        public ProjectBuilder withId(String id) {
            this.projectId = id;
            return this;
        }

        public ProjectBuilder withName(String name) {
            this.projectName = name;
            return this;
        }

        public ProjectBuilder withOwner(String owner) {
            this.projectOwner = owner;
            return this;
        }

        public ProjectBuilder withStatus(String status) {
            this.projectStatus = status;
            return this;
        }

        public ProjectBuilder withMembers(List<String> members) {
            this.projectMembers = new ArrayList<>(members);
            return this;
        }

        public ProjectBuilder addMember(String member) {
            this.projectMembers.add(member);
            return this;
        }

        public ProjectBuilder withStartDate(LocalDate startDate) {
            this.startDate = startDate;
            return this;
        }

        public ProjectBuilder withEndDate(LocalDate endDate) {
            this.endDate = endDate;
            return this;
        }

        public Project build() {
            Project project = new Project();
            project.setProjectId(projectId);
            project.setProjectName(projectName);
            project.setProjectType(projectType);
            project.setProjectOwner(projectOwner);
            project.setProjectMembers(projectMembers);
            project.setStartDate(startDate);
            project.setEndDate(endDate);
            project.setProjectStatus(projectStatus);
            project.setCreatedAt(createdAt);
            return project;
        }
    }

    public static class TaskBuilder {
        private String taskId = DEFAULT_TASK_ID;
        private String projectId = DEFAULT_PROJECT_ID;
        private String taskName = "前端页面开发";
        private String taskAssignee = "user_dev_01";
        private String taskStatus = Constants.TASK_STATUS_PENDING;
        private String taskPriority = Constants.TASK_PRIORITY_MEDIUM;
        private LocalDate startDate = LocalDate.now();
        private LocalDate dueDate = LocalDate.now().plusDays(7);
        private Integer progress = 0;
        private Integer estimatedHours = 40;
        private Integer actualHours = 0;
        private String milestoneId = null;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime completedAt = null;

        public TaskBuilder withId(String id) {
            this.taskId = id;
            return this;
        }

        public TaskBuilder withProjectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public TaskBuilder withName(String name) {
            this.taskName = name;
            return this;
        }

        public TaskBuilder withAssignee(String assignee) {
            this.taskAssignee = assignee;
            return this;
        }

        public TaskBuilder withStatus(String status) {
            this.taskStatus = status;
            return this;
        }

        public TaskBuilder withPriority(String priority) {
            this.taskPriority = priority;
            return this;
        }

        public TaskBuilder withDueDate(LocalDate dueDate) {
            this.dueDate = dueDate;
            return this;
        }

        public TaskBuilder withProgress(Integer progress) {
            this.progress = progress;
            return this;
        }

        public TaskBuilder withEstimatedHours(Integer hours) {
            this.estimatedHours = hours;
            return this;
        }

        public TaskBuilder withActualHours(Integer hours) {
            this.actualHours = hours;
            return this;
        }

        public TaskBuilder withMilestoneId(String milestoneId) {
            this.milestoneId = milestoneId;
            return this;
        }

        public TaskBuilder withCompletedAt(LocalDateTime completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public Task build() {
            Task task = new Task();
            task.setTaskId(taskId);
            task.setProjectId(projectId);
            task.setTaskName(taskName);
            task.setTaskAssignee(taskAssignee);
            task.setTaskStatus(taskStatus);
            task.setTaskPriority(taskPriority);
            task.setStartDate(startDate);
            task.setDueDate(dueDate);
            task.setProgress(progress);
            task.setEstimatedHours(estimatedHours);
            task.setActualHours(actualHours);
            task.setMilestoneId(milestoneId);
            task.setCreatedAt(createdAt);
            task.setCompletedAt(completedAt);
            return task;
        }
    }

    public static class MilestoneBuilder {
        private String milestoneId = DEFAULT_MILESTONE_ID;
        private String projectId = DEFAULT_PROJECT_ID;
        private String milestoneName = "第一阶段完成";
        private LocalDate milestoneDate = LocalDate.now().plusDays(15);
        private List<String> milestoneTasks = new ArrayList<>();
        private String status = Constants.MILESTONE_STATUS_PENDING;
        private Integer progress = 0;

        public MilestoneBuilder withId(String id) {
            this.milestoneId = id;
            return this;
        }

        public MilestoneBuilder withProjectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public MilestoneBuilder withName(String name) {
            this.milestoneName = name;
            return this;
        }

        public MilestoneBuilder withDate(LocalDate date) {
            this.milestoneDate = date;
            return this;
        }

        public MilestoneBuilder withTasks(List<String> tasks) {
            this.milestoneTasks = new ArrayList<>(tasks);
            return this;
        }

        public MilestoneBuilder withStatus(String status) {
            this.status = status;
            return this;
        }

        public MilestoneBuilder withProgress(Integer progress) {
            this.progress = progress;
            return this;
        }

        public Milestone build() {
            Milestone milestone = new Milestone();
            milestone.setMilestoneId(milestoneId);
            milestone.setProjectId(projectId);
            milestone.setMilestoneName(milestoneName);
            milestone.setMilestoneDate(milestoneDate);
            milestone.setMilestoneTasks(milestoneTasks);
            milestone.setStatus(status);
            milestone.setProgress(progress);
            return milestone;
        }
    }

    public static class RiskBuilder {
        private String riskId = "risk_001";
        private String projectId = DEFAULT_PROJECT_ID;
        private String taskId = DEFAULT_TASK_ID;
        private String riskType = Constants.RISK_TYPE_SCHEDULE_DELAY;
        private String riskDescription = "任务可能延期完成";
        private String riskLevel = Constants.RISK_LEVEL_MEDIUM;
        private String riskStatus = Constants.RISK_STATUS_IDENTIFIED;
        private LocalDateTime identifiedAt = LocalDateTime.now();
        private LocalDateTime resolvedAt = null;

        public RiskBuilder withId(String id) {
            this.riskId = id;
            return this;
        }

        public RiskBuilder withProjectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public RiskBuilder withTaskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public RiskBuilder withType(String type) {
            this.riskType = type;
            return this;
        }

        public RiskBuilder withDescription(String description) {
            this.riskDescription = description;
            return this;
        }

        public RiskBuilder withLevel(String level) {
            this.riskLevel = level;
            return this;
        }

        public RiskBuilder withStatus(String status) {
            this.riskStatus = status;
            return this;
        }

        public Risk build() {
            Risk risk = new Risk();
            risk.setRiskId(riskId);
            risk.setProjectId(projectId);
            risk.setTaskId(taskId);
            risk.setRiskType(riskType);
            risk.setRiskDescription(riskDescription);
            risk.setRiskLevel(riskLevel);
            risk.setRiskStatus(riskStatus);
            risk.setIdentifiedAt(identifiedAt);
            risk.setResolvedAt(resolvedAt);
            return risk;
        }
    }

    public static class ProgressBuilder {
        private String progressId = "progress_001";
        private String projectId = DEFAULT_PROJECT_ID;
        private Integer totalTasks = 10;
        private Integer completedTasks = 5;
        private Integer inProgressTasks = 3;
        private Integer pendingTasks = 2;
        private Integer overallProgress = 50;
        private LocalDateTime updatedAt = LocalDateTime.now();

        public ProgressBuilder withId(String id) {
            this.progressId = id;
            return this;
        }

        public ProgressBuilder withProjectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public ProgressBuilder withTotalTasks(Integer totalTasks) {
            this.totalTasks = totalTasks;
            return this;
        }

        public ProgressBuilder withCompletedTasks(Integer completedTasks) {
            this.completedTasks = completedTasks;
            return this;
        }

        public ProgressBuilder withInProgressTasks(Integer inProgressTasks) {
            this.inProgressTasks = inProgressTasks;
            return this;
        }

        public ProgressBuilder withPendingTasks(Integer pendingTasks) {
            this.pendingTasks = pendingTasks;
            return this;
        }

        public ProgressBuilder withOverallProgress(Integer overallProgress) {
            this.overallProgress = overallProgress;
            return this;
        }

        public Progress build() {
            Progress progress = new Progress();
            progress.setProgressId(progressId);
            progress.setProjectId(projectId);
            progress.setTotalTasks(totalTasks);
            progress.setCompletedTasks(completedTasks);
            progress.setInProgressTasks(inProgressTasks);
            progress.setPendingTasks(pendingTasks);
            progress.setOverallProgress(overallProgress);
            progress.setUpdatedAt(updatedAt);
            return progress;
        }
    }

    public static class StatisticBuilder {
        private String statId = "stat_001";
        private String projectId = DEFAULT_PROJECT_ID;
        private LocalDate statDate = LocalDate.now();
        private Integer totalHours = 100;
        private Integer completedHours = 50;
        private Integer taskCompletionRate = 50;
        private Integer onTimeRate = 80;

        public StatisticBuilder withId(String id) {
            this.statId = id;
            return this;
        }

        public StatisticBuilder withProjectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public StatisticBuilder withStatDate(LocalDate date) {
            this.statDate = date;
            return this;
        }

        public StatisticBuilder withTotalHours(Integer hours) {
            this.totalHours = hours;
            return this;
        }

        public StatisticBuilder withCompletedHours(Integer hours) {
            this.completedHours = hours;
            return this;
        }

        public StatisticBuilder withCompletionRate(Integer rate) {
            this.taskCompletionRate = rate;
            return this;
        }

        public StatisticBuilder withOnTimeRate(Integer rate) {
            this.onTimeRate = rate;
            return this;
        }

        public Statistic build() {
            Statistic statistic = new Statistic();
            statistic.setStatId(statId);
            statistic.setProjectId(projectId);
            statistic.setStatDate(statDate);
            statistic.setTotalHours(totalHours);
            statistic.setCompletedHours(completedHours);
            statistic.setTaskCompletionRate(taskCompletionRate);
            statistic.setOnTimeRate(onTimeRate);
            return statistic;
        }
    }

    public static class NotificationBuilder {
        private String notificationId = "notif_001";
        private String projectId = DEFAULT_PROJECT_ID;
        private String taskId = DEFAULT_TASK_ID;
        private String recipientId = "user_dev_01";
        private String notificationType = Constants.NOTIFICATION_TYPE_TASK_ASSIGNED;
        private String title = "任务分配通知";
        private String content = "您被分配了新任务";
        private Boolean isRead = false;
        private LocalDateTime createdAt = LocalDateTime.now();

        public NotificationBuilder withId(String id) {
            this.notificationId = id;
            return this;
        }

        public NotificationBuilder withProjectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public NotificationBuilder withTaskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public NotificationBuilder withRecipient(String recipient) {
            this.recipientId = recipient;
            return this;
        }

        public NotificationBuilder withType(String type) {
            this.notificationType = type;
            return this;
        }

        public NotificationBuilder withTitle(String title) {
            this.title = title;
            return this;
        }

        public NotificationBuilder withContent(String content) {
            this.content = content;
            return this;
        }

        public NotificationBuilder withRead(Boolean isRead) {
            this.isRead = isRead;
            return this;
        }

        public Notification build() {
            Notification notification = new Notification();
            notification.setNotificationId(notificationId);
            notification.setProjectId(projectId);
            notification.setTaskId(taskId);
            notification.setRecipientId(recipientId);
            notification.setNotificationType(notificationType);
            notification.setTitle(title);
            notification.setContent(content);
            notification.setRead(isRead);
            notification.setCreatedAt(createdAt);
            return notification;
        }
    }
}
