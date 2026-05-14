package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.model.Task;
import com.projmanage.model.TaskSortScore;
import com.projmanage.repository.TaskRepository;
import com.projmanage.repository.TaskSortScoreRepository;
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
public class TaskSortService {

    private final TaskRepository taskRepository;
    private final TaskSortScoreRepository taskSortScoreRepository;

    public TaskSortService(TaskRepository taskRepository,
                            TaskSortScoreRepository taskSortScoreRepository) {
        this.taskRepository = taskRepository;
        this.taskSortScoreRepository = taskSortScoreRepository;
    }

    @Transactional
    public TaskSortScore calculateAndSaveSortScore(Task task) {
        Map<String, Long> assigneeLoad = calculateAssigneeLoad();
        return calculateAndSaveSortScore(task, assigneeLoad);
    }

    @Transactional
    public TaskSortScore calculateAndSaveSortScore(Task task, Map<String, Long> assigneeLoad) {
        Optional<TaskSortScore> existingScore = taskSortScoreRepository.findByTaskId(task.getTaskId());

        TaskSortScore sortScore;
        if (existingScore.isPresent()) {
            sortScore = existingScore.get();
        } else {
            sortScore = new TaskSortScore();
            sortScore.setScoreId(IdGenerator.generateProgressId());
            sortScore.setTaskId(task.getTaskId());
            sortScore.setProjectId(task.getProjectId());
            sortScore.setPriorityWeight(Constants.SORT_PRIORITY_WEIGHT);
            sortScore.setUrgencyWeight(Constants.SORT_URGENCY_WEIGHT);
            sortScore.setWorkloadWeight(Constants.SORT_WORKLOAD_WEIGHT);
            sortScore.setCreatedAt(LocalDateTime.now());
        }

        int priorityScore = calculatePriorityScore(task);
        int urgencyScore = calculateUrgencyScore(task);
        int workloadScore = calculateWorkloadScore(task, assigneeLoad);

        int compositeScore = calculateCompositeScore(priorityScore, urgencyScore, workloadScore);

        sortScore.setPriorityScore(priorityScore);
        sortScore.setUrgencyScore(urgencyScore);
        sortScore.setWorkloadScore(workloadScore);
        sortScore.setCompositeScore(compositeScore);
        sortScore.setUpdatedAt(LocalDateTime.now());

        return taskSortScoreRepository.save(sortScore);
    }

    private int calculatePriorityScore(Task task) {
        if (task.getTaskPriority() == null) {
            return 33;
        }

        switch (task.getTaskPriority()) {
            case Constants.TASK_PRIORITY_HIGH:
                return 100;
            case Constants.TASK_PRIORITY_MEDIUM:
                return 66;
            case Constants.TASK_PRIORITY_LOW:
                return 33;
            default:
                return 33;
        }
    }

    private int calculateUrgencyScore(Task task) {
        if (task.getDueDate() == null) {
            return 50;
        }

        if (Constants.TASK_STATUS_COMPLETED.equals(task.getTaskStatus())) {
            return 0;
        }

        LocalDate today = LocalDate.now();
        long daysUntil = today.until(task.getDueDate(), java.time.temporal.ChronoUnit.DAYS);

        if (daysUntil < 0) {
            return 100;
        } else if (daysUntil == 0) {
            return 95;
        } else if (daysUntil <= 1) {
            return 90;
        } else if (daysUntil <= 3) {
            return 75;
        } else if (daysUntil <= 7) {
            return 50;
        } else if (daysUntil <= 14) {
            return 30;
        } else if (daysUntil <= 30) {
            return 15;
        } else {
            return 5;
        }
    }

    private int calculateWorkloadScore(Task task, Map<String, Long> assigneeLoad) {
        if (task.getTaskAssignee() == null) {
            return 50;
        }

        long load = assigneeLoad.getOrDefault(task.getTaskAssignee(), 0L);

        if (load == 0) {
            return 100;
        } else if (load == 1) {
            return 80;
        } else if (load <= 3) {
            return 60;
        } else if (load <= 5) {
            return 40;
        } else if (load <= 10) {
            return 20;
        } else {
            return 5;
        }
    }

    private int calculateCompositeScore(int priorityScore, int urgencyScore, int workloadScore) {
        double weightedScore =
                priorityScore * Constants.SORT_PRIORITY_WEIGHT +
                urgencyScore * Constants.SORT_URGENCY_WEIGHT +
                workloadScore * Constants.SORT_WORKLOAD_WEIGHT;

        return (int) Math.round(weightedScore);
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

    @Transactional
    public void refreshAllSortScores(String projectId) {
        List<Task> tasks = taskRepository.findByProjectId(projectId);
        Map<String, Long> assigneeLoad = calculateAssigneeLoad();

        for (Task task : tasks) {
            calculateAndSaveSortScore(task, assigneeLoad);
        }
    }

    public List<Task> getTasksSortedByCompositeScore(String projectId) {
        refreshAllSortScores(projectId);

        List<TaskSortScore> sortedScores = taskSortScoreRepository.findByProjectIdOrderByCompositeScoreDesc(projectId);

        List<String> sortedTaskIds = sortedScores.stream()
                .map(TaskSortScore::getTaskId)
                .collect(Collectors.toList());

        List<Task> tasks = taskRepository.findByProjectId(projectId);
        Map<String, Task> taskMap = new HashMap<>();
        for (Task task : tasks) {
            taskMap.put(task.getTaskId(), task);
        }

        List<Task> sortedTasks = new ArrayList<>();
        for (String taskId : sortedTaskIds) {
            Task task = taskMap.get(taskId);
            if (task != null) {
                sortedTasks.add(task);
            }
        }

        for (Task task : tasks) {
            if (!sortedTasks.contains(task)) {
                sortedTasks.add(task);
            }
        }

        return sortedTasks;
    }

    public List<Task> getTasksSortedByPriority(String projectId) {
        List<Task> tasks = taskRepository.findByProjectId(projectId);
        return tasks.stream()
                .sorted(Comparator.comparingInt(this::getPriorityWeight).reversed())
                .collect(Collectors.toList());
    }

    public List<Task> getTasksSortedByUrgency(String projectId) {
        List<Task> tasks = taskRepository.findByProjectId(projectId);
        return tasks.stream()
                .sorted(Comparator.comparingInt(this::getUrgencyWeight).reversed())
                .collect(Collectors.toList());
    }

    public List<Task> getTasksSortedByWorkload(String projectId) {
        List<Task> tasks = taskRepository.findByProjectId(projectId);
        Map<String, Long> assigneeLoad = calculateAssigneeLoad();

        return tasks.stream()
                .sorted(Comparator.comparingLong(task -> {
                    String assignee = task.getTaskAssignee();
                    return assignee == null ? 0L : assigneeLoad.getOrDefault(assignee, 0L);
                }))
                .collect(Collectors.toList());
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

    private int getUrgencyWeight(Task task) {
        return calculateUrgencyScore(task);
    }

    public List<TaskSortScore> getTaskSortScoresByProject(String projectId) {
        return taskSortScoreRepository.findByProjectIdOrderByCompositeScoreDesc(projectId);
    }

    public Optional<TaskSortScore> getTaskSortScore(String taskId) {
        return taskSortScoreRepository.findByTaskId(taskId);
    }
}
