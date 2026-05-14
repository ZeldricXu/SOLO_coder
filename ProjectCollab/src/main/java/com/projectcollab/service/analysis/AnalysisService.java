package com.projectcollab.service.analysis;

import com.projectcollab.entity.Project;
import com.projectcollab.entity.ProjectMember;
import com.projectcollab.entity.ProjectStatistics;
import com.projectcollab.entity.Task;
import com.projectcollab.repository.ProjectMemberRepository;
import com.projectcollab.repository.ProjectRepository;
import com.projectcollab.repository.ProjectStatisticsRepository;
import com.projectcollab.repository.TaskRepository;
import com.projectcollab.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class AnalysisService {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProjectMemberRepository memberRepository;

    @Autowired
    private ProjectStatisticsRepository statisticsRepository;

    public long getTotalProjects() {
        return projectRepository.count();
    }

    public long getTotalTasks() {
        return taskRepository.count();
    }

    public long getCompletedTasks() {
        return taskRepository.findByTaskStatus("completed").size();
    }

    public int getActiveProjectCount() {
        return projectRepository.findByProjectStatus("in_progress").size();
    }

    public int getCompletedProjectCount() {
        return projectRepository.findByProjectStatus("completed").size();
    }

    @Transactional
    public void updateTaskStatistics(String projectId, boolean isCompletion) {
        String currentMonth = IdGenerator.getCurrentMonth();
        ProjectStatistics stats = getOrCreateStatistics(currentMonth);

        stats.setProjectCount((int) projectRepository.count());
        
        List<Task> allTasks = taskRepository.findAll();
        stats.setTaskCount(allTasks.size());
        
        long completedCount = allTasks.stream()
                .filter(t -> "completed".equals(t.getTaskStatus()))
                .count();
        stats.setCompletedTaskCount((int) completedCount);

        if (isCompletion) {
            updateAvgCompletionTime(stats, allTasks);
        }

        statisticsRepository.save(stats);
    }

    @Transactional
    public void updateDocumentStatistics(String projectId) {
        String currentMonth = IdGenerator.getCurrentMonth();
        ProjectStatistics stats = getOrCreateStatistics(currentMonth);
        stats.setDocumentCount(stats.getDocumentCount() + 1);
        statisticsRepository.save(stats);
    }

    @Transactional
    public void updateMemberStatistics() {
        String currentMonth = IdGenerator.getCurrentMonth();
        ProjectStatistics stats = getOrCreateStatistics(currentMonth);
        stats.setMemberCount((int) memberRepository.count());
        statisticsRepository.save(stats);
    }

    public ProjectStatistics getMonthlyStatistics(String month) {
        Optional<ProjectStatistics> stats = statisticsRepository.findByStatMonth(month);
        if (stats.isPresent()) {
            return stats.get();
        }
        return calculateCurrentStatistics();
    }

    private ProjectStatistics getOrCreateStatistics(String month) {
        Optional<ProjectStatistics> existing = statisticsRepository.findByStatMonth(month);
        if (existing.isPresent()) {
            return existing.get();
        }
        
        ProjectStatistics stats = new ProjectStatistics();
        stats.setStatId(IdGenerator.generateStatId());
        stats.setStatMonth(month);
        return stats;
    }

    private void updateAvgCompletionTime(ProjectStatistics stats, List<Task> allTasks) {
        List<Task> completedTasks = allTasks.stream()
                .filter(t -> "completed".equals(t.getTaskStatus()))
                .filter(t -> t.getStartedAt() != null && t.getCompletedAt() != null)
                .toList();

        if (!completedTasks.isEmpty()) {
            double totalDays = completedTasks.stream()
                    .mapToLong(t -> java.time.Duration.between(
                            t.getStartedAt(), t.getCompletedAt()).toDays())
                    .sum();
            stats.setAvgCompletionTime(totalDays / completedTasks.size());
        }
    }

    private ProjectStatistics calculateCurrentStatistics() {
        ProjectStatistics stats = new ProjectStatistics();
        stats.setStatId(IdGenerator.generateStatId());
        stats.setStatMonth(IdGenerator.getCurrentMonth());
        stats.setProjectCount((int) projectRepository.count());
        
        List<Task> allTasks = taskRepository.findAll();
        stats.setTaskCount(allTasks.size());
        
        long completedCount = allTasks.stream()
                .filter(t -> "completed".equals(t.getTaskStatus()))
                .count();
        stats.setCompletedTaskCount((int) completedCount);
        
        stats.setMemberCount((int) memberRepository.count());
        
        return stats;
    }
}
