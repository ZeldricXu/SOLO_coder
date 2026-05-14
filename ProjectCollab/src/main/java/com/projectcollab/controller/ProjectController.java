package com.projectcollab.controller;

import com.projectcollab.dto.ApiResponse;
import com.projectcollab.dto.CreateProjectRequest;
import com.projectcollab.entity.Project;
import com.projectcollab.service.project.ProjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    @Autowired
    private ProjectService projectService;

    @PostMapping
    public ApiResponse<Project> createProject(@RequestBody CreateProjectRequest request) {
        Project project = projectService.createProject(request);
        return ApiResponse.success(project);
    }

    @GetMapping
    public ApiResponse<List<Project>> getAllProjects() {
        List<Project> projects = projectService.getAllProjects();
        return ApiResponse.success(projects);
    }

    @GetMapping("/{projectId}")
    public ApiResponse<Project> getProject(@PathVariable String projectId) {
        return projectService.getProjectById(projectId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "项目不存在"));
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<Project>> getProjectsByStatus(@PathVariable String status) {
        List<Project> projects = projectService.getProjectsByStatus(status);
        return ApiResponse.success(projects);
    }

    @PutMapping("/{projectId}/status/{status}")
    public ApiResponse<Project> updateProjectStatus(@PathVariable String projectId, @PathVariable String status) {
        Project project = projectService.updateProjectStatus(projectId, status);
        return ApiResponse.success(project);
    }
}
