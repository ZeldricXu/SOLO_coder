package com.projectcollab.builder;

import com.projectcollab.dto.*;
import com.projectcollab.entity.*;
import com.projectcollab.util.IdGenerator;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class TestDataBuilder {

    public static final String TASK_PRIORITY_CRITICAL = "critical";
    public static final String TASK_PRIORITY_HIGH = "high";
    public static final String TASK_PRIORITY_NORMAL = "normal";
    public static final String TASK_PRIORITY_LOW = "low";

    public static final String PROJECT_STATUS_PLANNING = "planning";
    public static final String PROJECT_STATUS_IN_PROGRESS = "in_progress";
    public static final String PROJECT_STATUS_COMPLETED = "completed";
    public static final String PROJECT_STATUS_PAUSED = "paused";

    public static final String TASK_STATUS_PENDING = "pending";
    public static final String TASK_STATUS_ASSIGNED = "assigned";
    public static final String TASK_STATUS_IN_PROGRESS = "in_progress";
    public static final String TASK_STATUS_COMPLETED = "completed";

    public static final String STAGE_STATUS_PENDING = "pending";
    public static final String STAGE_STATUS_IN_PROGRESS = "in_progress";
    public static final String STAGE_STATUS_COMPLETED = "completed";

    public static final String MEMBER_STATUS_ACTIVE = "active";
    public static final String MEMBER_STATUS_INACTIVE = "inactive";

    public static Project buildProject() {
        return buildProject("测试项目", "development", PROJECT_STATUS_IN_PROGRESS);
    }

    public static Project buildProject(String name, String type, String status) {
        Project project = new Project();
        project.setProjectId(IdGenerator.generateProjectId());
        project.setProjectName(name);
        project.setProjectType(type);
        project.setProjectStatus(status);
        project.setProjectProgress(0);
        project.setProjectStart(LocalDate.now());
        project.setProjectEnd(LocalDate.now().plusDays(30));
        project.setCreatedAt(LocalDateTime.now());
        return project;
    }

    public static Project buildPlanningProject() {
        return buildProject("规划中项目", "development", PROJECT_STATUS_PLANNING);
    }

    public static Project buildInProgressProject() {
        return buildProject("进行中项目", "development", PROJECT_STATUS_IN_PROGRESS);
    }

    public static Project buildCompletedProject() {
        Project project = buildProject("已完成项目", "development", PROJECT_STATUS_COMPLETED);
        project.setProjectProgress(100);
        return project;
    }

    public static Project buildPausedProject() {
        Project project = buildProject("已暂停项目", "development", PROJECT_STATUS_PAUSED);
        project.setProjectProgress(30);
        return project;
    }

    public static ProjectMember buildMember(Project project, String userId) {
        return buildMember(project, userId, "developer", MEMBER_STATUS_ACTIVE);
    }

    public static ProjectMember buildMember(Project project, String userId, String role, String status) {
        ProjectMember member = new ProjectMember();
        member.setMemberId(IdGenerator.generateMemberId());
        member.setProject(project);
        member.setUserId(userId);
        member.setMemberRole(role);
        member.setMemberStatus(status);
        member.setTaskCount(0);
        member.setCompletedTaskCount(0);
        member.setCreatedAt(LocalDateTime.now());
        return member;
    }

    public static ProjectMember buildInactiveMember(Project project, String userId) {
        return buildMember(project, userId, "developer", MEMBER_STATUS_INACTIVE);
    }

    public static ProjectMember buildMemberWithTasks(Project project, String userId, int taskCount) {
        ProjectMember member = buildMember(project, userId);
        member.setTaskCount(taskCount);
        return member;
    }

    public static Stage buildStage(Project project, String name, String code, int order) {
        Stage stage = new Stage();
        stage.setStageId(IdGenerator.generateStageId());
        stage.setProject(project);
        stage.setStageName(name);
        stage.setStageCode(code);
        stage.setStageOrder(order);
        stage.setStageStatus(STAGE_STATUS_PENDING);
        stage.setStageProgress(0);
        stage.setProgressWarningThreshold(0);
        stage.setProgressCriticalThreshold(0);
        stage.setProgressReminderEnabled(false);
        return stage;
    }

    public static Stage buildStageWithReminders(Project project, String name, String code, int order, 
                                                int warningThreshold, int criticalThreshold) {
        Stage stage = buildStage(project, name, code, order);
        stage.setProgressWarningThreshold(warningThreshold);
        stage.setProgressCriticalThreshold(criticalThreshold);
        stage.setProgressReminderEnabled(true);
        return stage;
    }

    public static Stage buildDesignStage(Project project) {
        Stage stage = buildStageWithReminders(project, "设计阶段", "design", 1, 70, 50);
        stage.setStageStatus(STAGE_STATUS_IN_PROGRESS);
        return stage;
    }

    public static Stage buildDevelopmentStage(Project project) {
        Stage stage = buildStageWithReminders(project, "开发阶段", "development", 2, 60, 40);
        stage.setStageStatus(STAGE_STATUS_IN_PROGRESS);
        return stage;
    }

    public static Stage buildTestingStage(Project project) {
        Stage stage = buildStageWithReminders(project, "测试阶段", "testing", 3, 75, 50);
        stage.setStageStatus(STAGE_STATUS_PENDING);
        return stage;
    }

    public static Task buildTask(Project project, String name, String stage, String assignee) {
        return buildTask(project, name, stage, assignee, TASK_PRIORITY_NORMAL, TASK_STATUS_ASSIGNED);
    }

    public static Task buildTask(Project project, String name, String stage, String assignee, 
                                 String priority, String status) {
        Task task = new Task();
        task.setTaskId(IdGenerator.generateTaskId());
        task.setProject(project);
        task.setTaskName(name);
        task.setTaskStage(stage);
        task.setTaskAssignee(assignee);
        task.setTaskStatus(status);
        task.setTaskProgress(0);
        task.setTaskDeadline(LocalDate.now().plusDays(10));
        task.setTaskDescription("测试任务描述");
        task.setTaskPriority(priority);
        task.setLocked(false);
        task.setCreatedAt(LocalDateTime.now());
        return task;
    }

    public static Task buildPendingTask(Project project, String stage) {
        Task task = buildTask(project, "待分配任务", stage, null, TASK_PRIORITY_NORMAL, TASK_STATUS_PENDING);
        task.setTaskAssignee(null);
        return task;
    }

    public static Task buildAssignedTask(Project project, String stage, String assignee) {
        return buildTask(project, "已分配任务", stage, assignee, TASK_PRIORITY_NORMAL, TASK_STATUS_ASSIGNED);
    }

    public static Task buildInProgressTask(Project project, String stage, String assignee, int progress) {
        Task task = buildTask(project, "进行中任务", stage, assignee, TASK_PRIORITY_NORMAL, TASK_STATUS_IN_PROGRESS);
        task.setTaskProgress(progress);
        task.setStartedAt(LocalDateTime.now());
        return task;
    }

    public static Task buildCompletedTask(Project project, String stage, String assignee) {
        Task task = buildTask(project, "已完成任务", stage, assignee, TASK_PRIORITY_NORMAL, TASK_STATUS_COMPLETED);
        task.setTaskProgress(100);
        task.setStartedAt(LocalDateTime.now().minusDays(3));
        task.setCompletedAt(LocalDateTime.now());
        return task;
    }

    public static Task buildCriticalPriorityTask(Project project, String stage, String assignee) {
        return buildTask(project, "紧急任务", stage, assignee, TASK_PRIORITY_CRITICAL, TASK_STATUS_ASSIGNED);
    }

    public static Task buildHighPriorityTask(Project project, String stage, String assignee) {
        return buildTask(project, "高优先级任务", stage, assignee, TASK_PRIORITY_HIGH, TASK_STATUS_ASSIGNED);
    }

    public static Task buildLowPriorityTask(Project project, String stage, String assignee) {
        return buildTask(project, "低优先级任务", stage, assignee, TASK_PRIORITY_LOW, TASK_STATUS_ASSIGNED);
    }

    public static Task buildLockedTask(Project project, String stage, String assignee, String lockOwner) {
        Task task = buildAssignedTask(project, stage, assignee);
        task.setLocked(true);
        task.setLockOwner(lockOwner);
        task.setLockedAt(LocalDateTime.now());
        task.setLockTimeoutSeconds(600);
        return task;
    }

    public static Task buildExpiredLockTask(Project project, String stage, String assignee, String lockOwner) {
        Task task = buildLockedTask(project, stage, assignee, lockOwner);
        task.setLockedAt(LocalDateTime.now().minusHours(2));
        task.setLockTimeoutSeconds(60);
        return task;
    }

    public static Progress buildProgress(Project project, int value, int completed, int total) {
        Progress progress = new Progress();
        progress.setProgressId(IdGenerator.generateProgressId());
        progress.setProject(project);
        progress.setProgressValue(value);
        progress.setProgressTasksCompleted(completed);
        progress.setProgressTasksTotal(total);
        progress.setProgressTime(LocalDateTime.now());
        return progress;
    }

    public static Document buildDocument(Project project, String name, String uploader) {
        Document document = new Document();
        document.setDocId(IdGenerator.generateDocId());
        document.setProject(project);
        document.setDocName(name);
        document.setDocType("general");
        document.setDocSize(1024);
        document.setDocUploader(uploader);
        document.setUploadedAt(LocalDateTime.now());
        document.setDocPath("/documents/" + document.getDocId());
        document.setShared(false);
        return document;
    }

    public static Document buildSharedDocument(Project project, String name, String uploader) {
        Document document = buildDocument(project, name, uploader);
        document.setShared(true);
        return document;
    }

    public static Reminder buildReminder(Project project, String type, String content) {
        Reminder reminder = new Reminder();
        reminder.setReminderId(IdGenerator.generateReminderId());
        reminder.setProject(project);
        reminder.setReminderType(type);
        reminder.setReminderContent(content);
        reminder.setReminderTime(LocalDateTime.now());
        reminder.setReminderStatus("pending");
        reminder.setCreatedAt(LocalDateTime.now());
        return reminder;
    }

    public static CreateProjectRequest buildCreateProjectRequest() {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setProjectName("新项目");
        request.setProjectType("development");
        request.setProjectStart(LocalDate.now());
        request.setProjectEnd(LocalDate.now().plusDays(30));
        return request;
    }

    public static CreateProjectRequest buildCreateProjectRequest(String name, String type) {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setProjectName(name);
        request.setProjectType(type);
        request.setProjectStart(LocalDate.now());
        request.setProjectEnd(LocalDate.now().plusDays(30));
        return request;
    }

    public static CreateTaskRequest buildCreateTaskRequest(String projectId) {
        return buildCreateTaskRequest(projectId, "新任务", TASK_PRIORITY_NORMAL);
    }

    public static CreateTaskRequest buildCreateTaskRequest(String projectId, String taskName, String priority) {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setProjectId(projectId);
        request.setTaskName(taskName);
        request.setTaskDeadline(LocalDate.now().plusDays(10));
        request.setTaskStage("development");
        request.setTaskDescription("这是一个测试任务");
        request.setTaskPriority(priority);
        return request;
    }

    public static CreateTaskRequest buildCriticalPriorityTaskRequest(String projectId) {
        return buildCreateTaskRequest(projectId, "紧急任务", TASK_PRIORITY_CRITICAL);
    }

    public static CreateTaskRequest buildHighPriorityTaskRequest(String projectId) {
        return buildCreateTaskRequest(projectId, "高优先级任务", TASK_PRIORITY_HIGH);
    }

    public static CreateTaskRequest buildLowPriorityTaskRequest(String projectId) {
        return buildCreateTaskRequest(projectId, "低优先级任务", TASK_PRIORITY_LOW);
    }

    public static UploadDocumentRequest buildUploadDocumentRequest(String projectId) {
        UploadDocumentRequest request = new UploadDocumentRequest();
        request.setProjectId(projectId);
        request.setDocName("测试文档.pdf");
        request.setDocType("design");
        request.setDocSize(1024);
        request.setDocUploader("user_001");
        request.setShareWithMembers(true);
        return request;
    }

    public static UploadDocumentRequest buildUploadDocumentRequest(String projectId, String name, String uploader, boolean share) {
        UploadDocumentRequest request = new UploadDocumentRequest();
        request.setProjectId(projectId);
        request.setDocName(name);
        request.setDocType("general");
        request.setDocSize(1024);
        request.setDocUploader(uploader);
        request.setShareWithMembers(share);
        return request;
    }

    public static UpdateProgressRequest buildUpdateProgressRequest(String taskId, int progress) {
        UpdateProgressRequest request = new UpdateProgressRequest();
        request.setTaskId(taskId);
        request.setTaskProgress(progress);
        return request;
    }

    public static LockTaskRequest buildLockTaskRequest(String taskId, String userId, String priority) {
        LockTaskRequest request = new LockTaskRequest();
        request.setTaskId(taskId);
        request.setUserId(userId);
        request.setTaskPriority(priority);
        return request;
    }

    public static AddMemberRequest buildAddMemberRequest(String projectId, String userId) {
        return buildAddMemberRequest(projectId, userId, "developer");
    }

    public static AddMemberRequest buildAddMemberRequest(String projectId, String userId, String role) {
        AddMemberRequest request = new AddMemberRequest();
        request.setProjectId(projectId);
        request.setUserId(userId);
        request.setMemberRole(role);
        return request;
    }

    public static AddStageRequest buildAddStageRequest(String projectId, String name, String code, int order) {
        AddStageRequest request = new AddStageRequest();
        request.setProjectId(projectId);
        request.setStageName(name);
        request.setStageCode(code);
        request.setStageOrder(order);
        return request;
    }

    public static HistoryRecord buildHistoryRecord(String projectId, String actionType) {
        HistoryRecord record = new HistoryRecord();
        record.setHistoryId(IdGenerator.generateHistoryId());
        record.setProjectId(projectId);
        record.setActionType(actionType);
        record.setActionContent("测试历史记录");
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }
}
