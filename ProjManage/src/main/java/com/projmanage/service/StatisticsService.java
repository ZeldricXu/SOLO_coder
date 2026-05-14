package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.model.Statistic;
import com.projmanage.model.Task;
import com.projmanage.repository.StatisticRepository;
import com.projmanage.repository.TaskRepository;
import com.projmanage.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class StatisticsService {

    private final StatisticRepository statisticRepository;
    private final TaskRepository taskRepository;

    public StatisticsService(StatisticRepository statisticRepository, TaskRepository taskRepository) {
        this.statisticRepository = statisticRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional
    public void updateTaskStatistics(String projectId) {
        List<Task> tasks = taskRepository.findByProjectId(projectId);

        int totalTasks = tasks.size();
        int completedTasks = 0;
        int onTimeTasks = 0;
        int totalEstimatedHours = 0;
        int totalActualHours = 0;

        LocalDate today = LocalDate.now();

        for (Task task : tasks) {
            if (task.getEstimatedHours() != null) {
                totalEstimatedHours += task.getEstimatedHours();
            }
            if (task.getActualHours() != null) {
                totalActualHours += task.getActualHours();
            }

            if (Constants.TASK_STATUS_COMPLETED.equals(task.getTaskStatus())) {
                completedTasks++;
                if (task.getDueDate() != null && task.getCompletedAt() != null) {
                    LocalDate completedDate = task.getCompletedAt().toLocalDate();
                    if (!completedDate.isAfter(task.getDueDate())) {
                        onTimeTasks++;
                    }
                }
            }
        }

        int taskCompletionRate = totalTasks > 0 ? (completedTasks * 100) / totalTasks : 0;
        int onTimeRate = completedTasks > 0 ? (onTimeTasks * 100) / completedTasks : 100;

        List<Statistic> existingStats = statisticRepository.findByProjectId(projectId);
        Statistic todayStat = null;

        for (Statistic stat : existingStats) {
            if (stat.getStatDate().equals(today)) {
                todayStat = stat;
                break;
            }
        }

        if (todayStat == null) {
            todayStat = new Statistic();
            todayStat.setStatId(IdGenerator.generateStatisticId());
            todayStat.setProjectId(projectId);
            todayStat.setStatDate(today);
        }

        todayStat.setTotalHours(totalEstimatedHours);
        todayStat.setCompletedHours(totalActualHours);
        todayStat.setTaskCompletionRate(taskCompletionRate);
        todayStat.setOnTimeRate(onTimeRate);

        statisticRepository.save(todayStat);
    }

    public Optional<Statistic> getTodayStatistics(String projectId) {
        LocalDate today = LocalDate.now();
        List<Statistic> stats = statisticRepository.findByProjectId(projectId);

        for (Statistic stat : stats) {
            if (stat.getStatDate().equals(today)) {
                return Optional.of(stat);
            }
        }

        return Optional.empty();
    }

    public List<Statistic> getStatisticsByProject(String projectId) {
        return statisticRepository.findByProjectIdOrderByStatDateDesc(projectId);
    }
}
