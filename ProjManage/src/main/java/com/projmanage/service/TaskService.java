package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.dto.CreateTaskRequest;
import com.projmanage.exception.BusinessException;
import com.projmanage.model.Project;
import com.projmanage.model.Task;
import com.projmanage.repository.TaskRepository;
import com.projmanage.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ProjectService projectService;
    private final ProgressService progressService;
    private final CollaborationService collaborationService;
    private final StatisticsService statisticsService;
    private final MilestoneService milestoneService;
    private final ProjectActivityService projectActivityService;
    private final TaskSortService taskSortService;

    public TaskService(TaskRepository taskRepository,
                       ProjectService projectService,
                       ProgressService progressService,
                       CollaborationService collaborationService,
                       StatisticsService statisticsService,
                       MilestoneService milestoneService,
                       ProjectActivityService projectActivityService,
                       TaskSortService taskSortService) {
        this.taskRepository = taskRepository;
        this.projectService = projectService;
        this.progressService = progressService;
        this.collaborationService = collaborationService;
        this.statisticsService = statisticsService;
        this.milestoneService = milestoneService;
        this.projectActivityService = projectActivityService;
        this.taskSortService = taskSortService;
    }

    @Transactional
    public String createTask(CreateTaskRequest request) {
        Optional<Project> projectOpt = projectService.getProjectById(request.getProjectId());
        if (!projectOpt.isPresent()) {
            throw new BusinessException(404, "项目不存在");
        }

        Project project = projectOpt.get();
        if (Constants.PROJECT_STATUS_COMPLETED.equals(project.getProjectStatus())) {
            throw new BusinessException(400, "项目已完成，无法创建任务");
        }

        if (request.getTaskAssignee() != null && !request.getTaskAssignee().isEmpty()) {
            if (!projectService.isMemberOfProject(request.getProjectId(), request.getTaskAssignee())) {
                throw new BusinessException(400, "任务负责人不是项目成员");
            }
        }

        Task task = new Task();
        task.setTaskId(IdGenerator.generateTaskId());
        task.setProjectId(request.getProjectId());
        task.setTaskName(request.getTaskName());
        task.setTaskAssignee(request.getTaskAssignee());
        task.setTaskStatus(Constants.TASK_STATUS_PENDING);
        task.setTaskPriority(request.getTaskPriority() != null ? request.getTaskPriority() : Constants.TASK_PRIORITY_MEDIUM);
        task.setDueDate(request.getDueDate());
        task.setStartDate(LocalDate.now());
        task.setProgress(0);
        task.setEstimatedHours(request.getEstimatedHours());
        task.setActualHours(0);
        task.setCreatedAt(LocalDateTime.now());

        taskRepository.save(task);

        taskSortService.calculateAndSaveSortScore(task);

        if (request.getTaskAssignee() != null && !request.getTaskAssignee().isEmpty()) {
            collaborationService.sendNotification(
                    request.getTaskAssignee(),
                    request.getProjectId(),
                    task.getTaskId(),
                    Constants.NOTIFICATION_TYPE_TASK_ASSIGNED,
                    "任务分配通知",
                    "您被分配了新任务: " + request.getTaskName()
            );
        }

        projectActivityService.recordActivity(request.getProjectId());

        progressService.updateProgress(request.getProjectId());

        statisticsService.updateTaskStatistics(request.getProjectId());

        return task.getTaskId();
    }

    public Optional<Task> getTaskById(String taskId) {
        return taskRepository.findById(taskId);
    }

    public List<Task> getTasksByProjectId(String projectId) {
        return taskRepository.findByProjectId(projectId);
    }

    public List<Task> getTasksByAssignee(String assigneeId) {
        return taskRepository.findByTaskAssignee(assigneeId);
    }

    @Transactional
    public void updateTaskProgress(String taskId, Integer progress, Integer actualHours) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "任务不存在"));

        if (Constants.TASK_STATUS_COMPLETED.equals(task.getTaskStatus())) {
            throw new BusinessException(400, "任务已完成，无法更新进度");
        }

        if (progress < 0 || progress > 100) {
            throw new BusinessException(400, "进度值必须在0-100之间");
        }

        task.setProgress(progress);

        if (progress == 100) {
            task.setTaskStatus(Constants.TASK_STATUS_COMPLETED);
            task.setCompletedAt(LocalDateTime.now());
        } else if (progress > 0) {
            task.setTaskStatus(Constants.TASK_STATUS_IN_PROGRESS);
        }

        if (actualHours != null) {
            task.setActualHours(actualHours);
        }

        taskRepository.save(task);

        taskSortService.calculateAndSaveSortScore(task);

        projectActivityService.recordActivity(task.getProjectId());

        progressService.updateProgress(task.getProjectId());

        if (task.getMilestoneId() != null) {
            milestoneService.updateMilestoneProgress(task.getMilestoneId());
        }

        statisticsService.updateTaskStatistics(task.getProjectId());
    }

    @Transactional
    public void updateTaskStatus(String taskId, String status) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "任务不存在"));

        task.setTaskStatus(status);
        if (Constants.TASK_STATUS_COMPLETED.equals(status)) {
            task.setCompletedAt(LocalDateTime.now());
            task.setProgress(100);
        }

        taskRepository.save(task);

        taskSortService.calculateAndSaveSortScore(task);

        projectActivityService.recordActivity(task.getProjectId());

        progressService.updateProgress(task.getProjectId());
        statisticsService.updateTaskStatistics(task.getProjectId());
    }

    @Transactional
    public void assignTask(String taskId, String assigneeId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "任务不存在"));

        if (!projectService.isMemberOfProject(task.getProjectId(), assigneeId)) {
            throw new BusinessException(400, "任务负责人不是项目成员");
        }

        task.setTaskAssignee(assigneeId);
        taskRepository.save(task);

        taskSortService.calculateAndSaveSortScore(task);

        projectActivityService.recordActivity(task.getProjectId());

        collaborationService.sendNotification(
                assigneeId,
                task.getProjectId(),
                task.getTaskId(),
                Constants.NOTIFICATION_TYPE_TASK_ASSIGNED,
                "任务分配通知",
                "您被分配了任务: " + task.getTaskName()
        );
    }

    @Transactional
    public void deleteTask(String taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "任务不存在"));

        String projectId = task.getProjectId();
        taskRepository.deleteById(taskId);

        projectActivityService.recordActivity(projectId);

        progressService.updateProgress(projectId);
        statisticsService.updateTaskStatistics(projectId);
    }

    public List<Task> getTasksSortedByCompositeScore(String projectId) {
        return taskSortService.getTasksSortedByCompositeScore(projectId);
    }

    public List<Task> getTasksSortedByPriority(String projectId) {
        return taskSortService.getTasksSortedByPriority(projectId);
    }

    public List<Task> getTasksSortedByUrgency(String projectId) {
        return taskSortService.getTasksSortedByUrgency(projectId);
    }

    public List<Task> getTasksSortedByWorkload(String projectId) {
        return taskSortService.getTasksSortedByWorkload(projectId);
    }

    public List<Task> getTasksSortedByDueDate(String projectId) {
        List<Task> tasks = taskRepository.findByProjectId(projectId);
        return tasks.stream()
                .sorted(Comparator.comparing(task -> task.getDueDate() == null ? LocalDate.MAX : task.getDueDate()))
                .collect(Collectors.toList());
    }

    public List<Task> getTasksMultiDimensionalSort(String projectId, String primarySort, String secondarySort) {
        List<Task> tasks = taskRepository.findByProjectId(projectId);
        if (tasks.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Long> assigneeLoad = calculateAssigneeLoad();

        Comparator<Task> comparator = buildComparator(primarySort, assigneeLoad);
        if (secondarySort != null && !secondarySort.isEmpty()) {
            comparator = comparator.thenComparing(buildComparator(secondarySort, assigneeLoad));
        }

        return tasks.stream()
                .sorted(comparator)
                .collect(Collectors.toList());
    }

    private Map<String, Long> calculateAssigneeLoad() {
        List<Task> allTasks = taskRepository.findAll();
        Map<String, Long> assigneeTaskCount = new HashMap<>();
        for (Task task : allTasks) {
            if (!Constants.TASK_STATUS_COMPLETED.equals(task.getTaskStatus()) && task.getTaskAssignee() != null) {
                assigneeTaskCount.merge(task.getTaskAssignee(), 1L, Long::sum);
            }
        }
        return assigneeTaskCount;
    }

    private Comparator<Task> buildComparator(String sortType, Map<String, Long> assigneeLoad) {
        switch (sortType) {
            case "priority_desc":
                return Comparator.comparingInt(this::getPriorityWeight).reversed();
            case "priority_asc":
                return Comparator.comparingInt(this::getPriorityWeight);
            case "due_date":
                return Comparator.comparing(task -> task.getDueDate() == null ? LocalDate.MAX : task.getDueDate());
            case "due_date_desc":
                return (t1, t2) -> {
                    LocalDate d1 = t1.getDueDate() == null ? LocalDate.MIN : t1.getDueDate();
                    LocalDate d2 = t2.getDueDate() == null ? LocalDate.MIN : t2.getDueDate();
                    return d2.compareTo(d1);
                };
            case "assignee_load":
                return Comparator.comparingLong(task -> {
                    String assignee = task.getTaskAssignee();
                    return assignee == null ? 0L : assigneeLoad.getOrDefault(assignee, 0L);
                });
            case "progress":
                return Comparator.comparingInt(task -> task.getProgress() == null ? 0 : task.getProgress());
            default:
                return Comparator.comparingInt(this::getPriorityWeight).reversed();
        }
    }

    private int getPriorityWeight(Task task) {
        if (task.getTaskPriority() == null) {
            return 1;
        }
        switch (task.getTaskPriority()) {
            case Constants.TASK_PRIORITY_HIGH:
                return 3;
            case Constants.TASK_PRIORITY_MEDIUM:
                return 2;
            case Constants.TASK_PRIORITY_LOW:
                return 1;
            default:
                return 1;
        }
    }

    public List<Task> getHighPriorityTasks(String projectId) {
        List<Task> tasks = taskRepository.findByProjectId(projectId);
        return tasks.stream()
                .filter(task -> Constants.TASK_PRIORITY_HIGH.equals(task.getTaskPriority()))
                .filter(task -> !Constants.TASK_STATUS_COMPLETED.equals(task.getTaskStatus()))
                .sorted(Comparator.comparing(task -> task.getDueDate() == null ? LocalDate.MAX : task.getDueDate()))
                .collect(Collectors.toList());
    }
}
