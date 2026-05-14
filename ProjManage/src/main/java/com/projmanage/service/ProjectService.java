package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.dto.CreateProjectRequest;
import com.projmanage.exception.BusinessException;
import com.projmanage.model.Project;
import com.projmanage.repository.ProjectRepository;
import com.projmanage.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Transactional
    public String createProject(CreateProjectRequest request) {
        if (request.getProjectName() == null || request.getProjectName().isEmpty()) {
            throw new BusinessException(400, "项目名称不能为空");
        }
        if (request.getProjectOwner() == null || request.getProjectOwner().isEmpty()) {
            throw new BusinessException(400, "项目负责人不能为空");
        }

        Project project = new Project();
        project.setProjectId(IdGenerator.generateProjectId());
        project.setProjectName(request.getProjectName());
        project.setProjectType(request.getProjectType() != null ? request.getProjectType() : "development");
        project.setProjectOwner(request.getProjectOwner());
        project.setProjectStatus(Constants.PROJECT_STATUS_IN_PROGRESS);
        project.setStartDate(LocalDate.now());
        project.setCreatedAt(LocalDateTime.now());

        projectRepository.save(project);
        return project.getProjectId();
    }

    public Optional<Project> getProjectById(String projectId) {
        return projectRepository.findById(projectId);
    }

    public List<Project> getAllProjects() {
        return projectRepository.findAll();
    }

    public List<Project> getProjectsByOwner(String ownerId) {
        return projectRepository.findByProjectOwner(ownerId);
    }

    @Transactional
    public void addMember(String projectId, String memberId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(404, "项目不存在"));

        if (!project.getProjectMembers().contains(memberId)) {
            project.getProjectMembers().add(memberId);
            projectRepository.save(project);
        }
    }

    @Transactional
    public void removeMember(String projectId, String memberId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(404, "项目不存在"));

        project.getProjectMembers().remove(memberId);
        projectRepository.save(project);
    }

    public boolean isMemberOfProject(String projectId, String memberId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            return false;
        }
        return project.getProjectOwner().equals(memberId) ||
                project.getProjectMembers().contains(memberId);
    }

    @Transactional
    public void updateProjectStatus(String projectId, String status) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(404, "项目不存在"));

        project.setProjectStatus(status);
        projectRepository.save(project);
    }

    @Transactional
    public void deleteProject(String projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(404, "项目不存在"));
        projectRepository.deleteById(projectId);
    }
}
