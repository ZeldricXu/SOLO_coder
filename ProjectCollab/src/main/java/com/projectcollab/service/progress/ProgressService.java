package com.projectcollab.service.progress;

import com.projectcollab.entity.Progress;
import com.projectcollab.entity.Project;
import com.projectcollab.entity.Task;
import com.projectcollab.repository.ProgressRepository;
import com.projectcollab.service.project.ProjectService;
import com.projectcollab.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProgressService {

    @Autowired
    private ProgressRepository progressRepository;

    @Autowired
    private ProjectService projectService;

    public List<Progress> getProgressByProjectId(String projectId) {
        return progressRepository.findByProject_ProjectIdOrderByProgressTimeDesc(projectId);
    }

    @Transactional
    public int calculateAndUpdateProjectProgress(Project project, List<Task> allTasks) {
        if (allTasks.isEmpty()) {
            return 0;
        }

        int completedTasks = 0;
        int totalProgress = 0;

        for (Task task : allTasks) {
            totalProgress += task.getTaskProgress();
            if ("completed".equals(task.getTaskStatus())) {
                completedTasks++;
            }
        }

        int projectProgress = totalProgress / allTasks.size();
        projectService.updateProjectProgress(project.getProjectId(), projectProgress);

        Progress progress = new Progress();
        progress.setProgressId(IdGenerator.generateProgressId());
        progress.setProject(project);
        progress.setProgressValue(projectProgress);
        progress.setProgressTasksCompleted(completedTasks);
        progress.setProgressTasksTotal(allTasks.size());
        progress.setProgressTime(LocalDateTime.now());
        progressRepository.save(progress);

        return projectProgress;
    }

    @Transactional
    public int recordTaskProgress(Task task) {
        Project project = task.getProject();
        List<Task> allTasks = project.getTasks();
        
        return calculateAndUpdateProjectProgress(project, allTasks);
    }
}
