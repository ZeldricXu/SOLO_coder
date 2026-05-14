package com.projmanage.controller;

import com.projmanage.dto.ApiResponse;
import com.projmanage.dto.CreateProjectRequest;
import com.projmanage.dto.ProgressResponse;
import com.projmanage.model.Project;
import com.projmanage.service.ProjectService;
import com.projmanage.service.ProgressService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProgressService progressService;

    public ProjectController(ProjectService projectService, ProgressService progressService) {
        this.projectService = projectService;
        this.progressService = progressService;
    }

    @PostMapping("/create")
    public ApiResponse<Map<String, String>> createProject(@RequestBody CreateProjectRequest request) {
        String projectId = projectService.createProject(request);
        Map<String, String> result = new HashMap<>();
        result.put("project_id", projectId);
        return ApiResponse.success(result);
    }

    @GetMapping("/{projectId}")
    public ApiResponse<Project> getProjectById(@PathVariable String projectId) {
        Optional<Project> projectOpt = projectService.getProjectById(projectId);
        if (projectOpt.isPresent()) {
            return ApiResponse.success(projectOpt.get());
        }
        return ApiResponse.error(404, "项目不存在");
    }

    @GetMapping
    public ApiResponse<List<Project>> getAllProjects() {
        return ApiResponse.success(projectService.getAllProjects());
    }

    @GetMapping("/progress")
    public ApiResponse<Map<String, Object>> getProjectProgress(@RequestParam String projectId) {
        ProgressResponse progress = progressService.getProjectProgress(projectId);
        Map<String, Object> result = new HashMap<>();
        result.put("overall_progress", progress.getOverallProgress());
        result.put("completed_tasks", progress.getCompletedTasks());
        result.put("total_tasks", progress.getTotalTasks());
        result.put("in_progress_tasks", progress.getInProgressTasks());
        result.put("pending_tasks", progress.getPendingTasks());

        Map<String, Object> data = new HashMap<>();
        data.put("progress", result);
        return ApiResponse.success(data);
    }

    @PostMapping("/{projectId}/members")
    public ApiResponse<Void> addMember(@PathVariable String projectId, @RequestParam String memberId) {
        projectService.addMember(projectId, memberId);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{projectId}/members/{memberId}")
    public ApiResponse<Void> removeMember(@PathVariable String projectId, @PathVariable String memberId) {
        projectService.removeMember(projectId, memberId);
        return ApiResponse.success(null);
    }

    @PutMapping("/{projectId}/status")
    public ApiResponse<Void> updateProjectStatus(@PathVariable String projectId, @RequestParam String status) {
        projectService.updateProjectStatus(projectId, status);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<Void> deleteProject(@PathVariable String projectId) {
        projectService.deleteProject(projectId);
        return ApiResponse.success(null);
    }
}
