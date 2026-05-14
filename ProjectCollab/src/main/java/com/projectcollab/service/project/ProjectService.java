package com.projectcollab.service.project;

import com.projectcollab.config.properties.ProjectTypeProperties;
import com.projectcollab.dto.CreateProjectRequest;
import com.projectcollab.entity.Project;
import com.projectcollab.exception.ProjectCollabException;
import com.projectcollab.repository.ProjectRepository;
import com.projectcollab.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ProjectService {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectTypeProperties typeProperties;

    public List<Project> getAllProjects() {
        return projectRepository.findAll();
    }

    public Optional<Project> getProjectById(String projectId) {
        return projectRepository.findById(projectId);
    }

    public Project getProjectOrThrow(String projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectCollabException(404, "项目不存在: " + projectId));
    }

    public ProjectTypeProperties getTypeProperties() {
        return typeProperties;
    }

    @Transactional
    public Project createProject(CreateProjectRequest request) {
        String projectType = request.getProjectType() != null ? request.getProjectType() : "development";
        
        if (!typeProperties.isValidType(projectType)) {
            throw new ProjectCollabException(400, "无效的项目类型: " + projectType);
        }

        Project project = new Project();
        project.setProjectId(IdGenerator.generateProjectId());
        project.setProjectName(request.getProjectName());
        project.setProjectType(projectType);
        project.setProjectStatus("in_progress");
        project.setProjectProgress(0);
        project.setProjectStart(request.getProjectStart());
        project.setProjectEnd(request.getProjectEnd());
        project.setCreatedAt(LocalDateTime.now());
        return projectRepository.save(project);
    }

    @Transactional
    public Project updateProjectStatus(String projectId, String status) {
        Project project = getProjectOrThrow(projectId);
        project.setProjectStatus(status);
        return projectRepository.save(project);
    }

    @Transactional
    public Project updateProjectProgress(String projectId, int progress) {
        Project project = getProjectOrThrow(projectId);
        project.setProjectProgress(progress);
        if (progress >= 100) {
            project.setProjectStatus("completed");
        }
        return projectRepository.save(project);
    }

    public void validateProjectStatusForTaskCreation(Project project) {
        String status = project.getProjectStatus();
        if ("completed".equals(status)) {
            throw new ProjectCollabException(400, "项目已完成，无法创建任务");
        }
        if ("paused".equals(status)) {
            throw new ProjectCollabException(400, "项目已暂停，无法创建任务");
        }
        if (!"in_progress".equals(status)) {
            throw new ProjectCollabException(400, "项目状态不允许创建任务");
        }
    }

    public List<Project> getProjectsByStatus(String status) {
        return projectRepository.findByProjectStatus(status);
    }

    public List<Project> getProjectsByType(String type) {
        return projectRepository.findByProjectType(type);
    }

    public List<String> getAvailableProjectTypes() {
        return typeProperties.getAllTypes();
    }
}
