package com.projectcollab.service.task;

import com.projectcollab.config.properties.ProgressReminderProperties;
import com.projectcollab.config.properties.TaskLockProperties;
import com.projectcollab.dto.CreateTaskRequest;
import com.projectcollab.dto.CreateTaskResponse;
import com.projectcollab.dto.UpdateProgressRequest;
import com.projectcollab.dto.UpdateProgressResponse;
import com.projectcollab.entity.Project;
import com.projectcollab.entity.ProjectMember;
import com.projectcollab.entity.Stage;
import com.projectcollab.entity.Task;
import com.projectcollab.exception.ProjectCollabException;
import com.projectcollab.repository.TaskRepository;
import com.projectcollab.service.analysis.AnalysisService;
import com.projectcollab.service.history.HistoryService;
import com.projectcollab.service.member.MemberService;
import com.projectcollab.service.progress.ProgressService;
import com.projectcollab.service.project.ProjectService;
import com.projectcollab.service.reminder.ReminderService;
import com.projectcollab.service.stage.StageService;
import com.projectcollab.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class TaskService {

    public static final String PRIORITY_CRITICAL = "critical";
    public static final String PRIORITY_HIGH = "high";
    public static final String PRIORITY_NORMAL = "normal";
    public static final String PRIORITY_LOW = "low";

    private final Map<String, ReentrantLock> taskLocks = new ConcurrentHashMap<>();

    @Autowired
    private TaskLockProperties lockProperties;

    @Autowired
    private ProgressReminderProperties reminderProperties;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private StageService stageService;

    @Autowired
    private ProgressService progressService;

    @Autowired
    private ReminderService reminderService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AnalysisService analysisService;

    public List<Task> getTasksByProjectId(String projectId) {
        return taskRepository.findByProject_ProjectId(projectId);
    }

    public Optional<Task> getTaskById(String taskId) {
        return taskRepository.findById(taskId);
    }

    public Task getTaskOrThrow(String taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new ProjectCollabException(404, "任务不存在: " + taskId));
    }

    public int getLockTimeoutForPriority(String priority) {
        return lockProperties.getTimeout(priority);
    }

    public TaskLockProperties getLockProperties() {
        return lockProperties;
    }

    @Transactional
    public CreateTaskResponse createTask(CreateTaskRequest request) {
        Project project = projectService.getProjectOrThrow(request.getProjectId());
        
        projectService.validateProjectStatusForTaskCreation(project);

        Task task = new Task();
        task.setTaskId(IdGenerator.generateTaskId());
        task.setProject(project);
        task.setTaskName(request.getTaskName());
        
        String stage = request.getTaskStage();
        if (stage == null) {
            stage = stageService.getCurrentStage(project.getProjectId());
        }
        task.setTaskStage(stage);
        
        task.setTaskDescription(request.getTaskDescription());
        task.setTaskDeadline(request.getTaskDeadline());
        task.setTaskStatus("pending");
        task.setTaskProgress(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setTaskPriority(request.getTaskPriority() != null ? request.getTaskPriority() : PRIORITY_NORMAL);
        task.setLocked(false);

        ProjectMember assignedMember = memberService.selectOptimalMember(project.getProjectId());
        task.setTaskAssignee(assignedMember.getUserId());
        task.setTaskStatus("assigned");

        Task savedTask = taskRepository.save(task);

        memberService.incrementTaskCount(assignedMember.getMemberId());

        reminderService.createTaskAssignmentNotification(savedTask);

        if (savedTask.getTaskDeadline() != null) {
            reminderService.createDeadlineReminder(savedTask);
        }

        analysisService.updateTaskStatistics(project.getProjectId(), false);

        historyService.recordTaskCreation(savedTask);

        progressService.calculateAndUpdateProjectProgress(
                project, taskRepository.findByProject_ProjectId(project.getProjectId()));

        return new CreateTaskResponse(savedTask.getTaskId(), savedTask.getTaskStatus());
    }

    @Transactional
    public boolean lockTask(String taskId, String userId, String priority) {
        Task task = getTaskOrThrow(taskId);
        
        String effectivePriority = priority != null ? priority : task.getTaskPriority();
        if (effectivePriority == null) {
            effectivePriority = PRIORITY_NORMAL;
        }

        if (task.isLocked()) {
            if (task.getLockOwner() != null && task.getLockOwner().equals(userId)) {
                return true;
            }
            if (task.getLockedAt() != null) {
                long elapsedSeconds = Duration.between(task.getLockedAt(), LocalDateTime.now()).getSeconds();
                if (elapsedSeconds >= task.getLockTimeoutSeconds()) {
                    task.setLocked(false);
                    task.setLockOwner(null);
                    task.setLockedAt(null);
                } else {
                    return false;
                }
            }
        }

        int timeout = getLockTimeoutForPriority(effectivePriority);
        task.setLocked(true);
        task.setLockOwner(userId);
        task.setLockTimeoutSeconds(timeout);
        task.setLockedAt(LocalDateTime.now());
        taskRepository.save(task);
        
        return true;
    }

    @Transactional
    public boolean unlockTask(String taskId, String userId) {
        Task task = getTaskOrThrow(taskId);
        
        if (!task.isLocked()) {
            return true;
        }
        
        if (task.getLockOwner() != null && !task.getLockOwner().equals(userId)) {
            throw new ProjectCollabException(403, "只有锁定者可以解锁任务");
        }
        
        task.setLocked(false);
        task.setLockOwner(null);
        task.setLockedAt(null);
        taskRepository.save(task);
        
        return true;
    }

    public boolean tryAcquireLock(String taskId, String userId, String priority, long waitTime, TimeUnit unit) 
            throws InterruptedException {
        ReentrantLock lock = taskLocks.computeIfAbsent(taskId, k -> new ReentrantLock());
        
        int timeoutSeconds = getLockTimeoutForPriority(priority);
        long adjustedWaitTime = waitTime > 0 ? waitTime : Math.min(timeoutSeconds / 2L, 30L);
        
        boolean acquired = lock.tryLock(adjustedWaitTime, unit);
        if (acquired) {
            try {
                return lockTask(taskId, userId, priority);
            } finally {
                lock.unlock();
            }
        }
        
        return false;
    }

    @Transactional
    public UpdateProgressResponse updateProgress(UpdateProgressRequest request) {
        Task task = getTaskOrThrow(request.getTaskId());

        validateTaskForProgressUpdate(task);

        int newProgress = request.getTaskProgress();
        if (newProgress < 0 || newProgress > 100) {
            throw new ProjectCollabException(400, "进度值必须在0-100之间");
        }

        task.setTaskProgress(newProgress);

        if (task.getStartedAt() == null && newProgress > 0) {
            task.setStartedAt(LocalDateTime.now());
            task.setTaskStatus("in_progress");
        }

        boolean isCompletion = false;
        if (newProgress >= 100) {
            task.setTaskStatus("completed");
            task.setCompletedAt(LocalDateTime.now());
            task.setTaskProgress(100);
            isCompletion = true;
            
            if (task.getTaskAssignee() != null) {
                memberService.decrementTaskCountAndIncrementCompleted(
                        task.getTaskAssignee(), 
                        task.getProject().getProjectId()
                );
            }
            
            reminderService.createTaskCompletionNotification(task);
            historyService.recordTaskCompletion(task);
        } else {
            historyService.recordProgressUpdate(task);
        }

        taskRepository.save(task);

        stageService.updateStageProgressIfNeeded(task);

        checkProgressReminders(task);

        Project project = task.getProject();
        List<Task> allTasks = taskRepository.findByProject_ProjectId(project.getProjectId());
        int projectProgress = progressService.calculateAndUpdateProjectProgress(project, allTasks);

        analysisService.updateTaskStatistics(project.getProjectId(), isCompletion);

        return new UpdateProgressResponse(projectProgress);
    }

    private void checkProgressReminders(Task task) {
        Optional<Stage> stageOpt = stageService.getStageByCode(
                task.getProject().getProjectId(), 
                task.getTaskStage()
        );
        
        if (stageOpt.isEmpty()) {
            return;
        }
        
        Stage stage = stageOpt.get();
        
        String stageCode = stage.getStageCode();
        int stageProgress = stage.getStageProgress();
        
        int warningThreshold;
        int criticalThreshold;
        boolean reminderEnabled;
        
        if (stage.getProgressWarningThreshold() > 0 || stage.getProgressCriticalThreshold() > 0) {
            warningThreshold = stage.getProgressWarningThreshold();
            criticalThreshold = stage.getProgressCriticalThreshold();
            reminderEnabled = stage.isProgressReminderEnabled();
        } else {
            warningThreshold = reminderProperties.getWarningThreshold(stageCode);
            criticalThreshold = reminderProperties.getCriticalThreshold(stageCode);
            reminderEnabled = reminderProperties.isReminderEnabled(stageCode);
        }
        
        if (!reminderEnabled) {
            return;
        }
        
        if (criticalThreshold > 0 && stageProgress < criticalThreshold) {
            reminderService.createProgressCriticalWarning(task.getProject(), stage, task);
        } else if (warningThreshold > 0 && stageProgress < warningThreshold) {
            reminderService.createProgressWarning(task.getProject(), stage, task);
        }
    }

    private void validateTaskForProgressUpdate(Task task) {
        String status = task.getTaskStatus();
        if ("completed".equals(status)) {
            throw new ProjectCollabException(400, "任务已完成，无法更新进度");
        }
        if ("cancelled".equals(status)) {
            throw new ProjectCollabException(400, "任务已取消，无法更新进度");
        }
    }

    @Transactional
    public Task startTask(String taskId) {
        Task task = getTaskOrThrow(taskId);
        
        if (!"assigned".equals(task.getTaskStatus())) {
            throw new ProjectCollabException(400, "只有已分配状态的任务可以开始");
        }
        
        task.setTaskStatus("in_progress");
        task.setStartedAt(LocalDateTime.now());
        
        historyService.recordProgressUpdate(task);
        
        return taskRepository.save(task);
    }

    @Transactional
    public Task completeTask(String taskId) {
        Task task = getTaskOrThrow(taskId);
        
        if ("completed".equals(task.getTaskStatus())) {
            throw new ProjectCollabException(400, "任务已经完成");
        }
        
        task.setTaskStatus("completed");
        task.setTaskProgress(100);
        task.setCompletedAt(LocalDateTime.now());
        
        if (task.getStartedAt() == null) {
            task.setStartedAt(LocalDateTime.now());
        }
        
        if (task.getTaskAssignee() != null) {
            memberService.decrementTaskCountAndIncrementCompleted(
                    task.getTaskAssignee(), 
                    task.getProject().getProjectId()
            );
        }
        
        reminderService.createTaskCompletionNotification(task);
        historyService.recordTaskCompletion(task);
        
        taskRepository.save(task);
        
        return task;
    }

    public List<Task> getTasksByStatus(String status) {
        return taskRepository.findByTaskStatus(status);
    }

    public List<Task> getTasksByAssignee(String assignee) {
        return taskRepository.findByTaskAssignee(assignee);
    }

    public List<Task> getTasksByPriority(String priority) {
        return taskRepository.findAll().stream()
                .filter(t -> priority.equals(t.getTaskPriority()))
                .toList();
    }
}
