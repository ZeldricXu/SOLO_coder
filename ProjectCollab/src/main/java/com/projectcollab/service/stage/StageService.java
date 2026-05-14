package com.projectcollab.service.stage;

import com.projectcollab.dto.AddStageRequest;
import com.projectcollab.entity.Project;
import com.projectcollab.entity.Stage;
import com.projectcollab.entity.Task;
import com.projectcollab.repository.StageRepository;
import com.projectcollab.repository.TaskRepository;
import com.projectcollab.service.project.ProjectService;
import com.projectcollab.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class StageService {

    @Autowired
    private StageRepository stageRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProjectService projectService;

    public List<Stage> getStagesByProjectId(String projectId) {
        return stageRepository.findByProject_ProjectIdOrderByStageOrderAsc(projectId);
    }

    public Optional<Stage> getStageByCode(String projectId, String stageCode) {
        return stageRepository.findByProject_ProjectIdAndStageCode(projectId, stageCode);
    }

    public String getCurrentStage(String projectId) {
        List<Stage> stages = stageRepository.findByProject_ProjectIdOrderByStageOrderAsc(projectId);
        
        for (Stage stage : stages) {
            if ("in_progress".equals(stage.getStageStatus())) {
                return stage.getStageCode();
            }
        }
        
        Optional<Stage> pendingStage = stageRepository.findByProject_ProjectIdAndStageStatusOrderByStageOrderAsc(
                projectId, "pending");
        if (pendingStage.isPresent()) {
            return pendingStage.get().getStageCode();
        }
        
        return stages.isEmpty() ? "default" : stages.get(0).getStageCode();
    }

    @Transactional
    public Stage addStage(AddStageRequest request) {
        Project project = projectService.getProjectOrThrow(request.getProjectId());
        
        Stage stage = new Stage();
        stage.setStageId(IdGenerator.generateStageId());
        stage.setProject(project);
        stage.setStageName(request.getStageName());
        stage.setStageCode(request.getStageCode());
        stage.setStageOrder(request.getStageOrder());
        stage.setStageStatus("pending");
        stage.setStageProgress(0);
        
        return stageRepository.save(stage);
    }

    @Transactional
    public void updateStageProgressIfNeeded(Task task) {
        String stageCode = task.getTaskStage();
        if (stageCode == null) {
            return;
        }

        Optional<Stage> optStage = stageRepository.findByProject_ProjectIdAndStageCode(
                task.getProject().getProjectId(), stageCode);
        
        if (optStage.isEmpty()) {
            return;
        }

        Stage stage = optStage.get();
        List<Task> stageTasks = taskRepository.findByProject_ProjectIdAndTaskStage(
                task.getProject().getProjectId(), stageCode);

        if (stageTasks.isEmpty()) {
            return;
        }

        int completedTasks = 0;
        for (Task t : stageTasks) {
            if ("completed".equals(t.getTaskStatus())) {
                completedTasks++;
            }
        }

        int progress = (completedTasks * 100) / stageTasks.size();
        stage.setStageProgress(progress);

        if (progress == 100) {
            stage.setStageStatus("completed");
        } else if (progress > 0) {
            stage.setStageStatus("in_progress");
        }

        stageRepository.save(stage);
    }
}
