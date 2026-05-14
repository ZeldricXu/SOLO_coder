package com.projectcollab.controller;

import com.projectcollab.dto.ApiResponse;
import com.projectcollab.dto.UpdateProgressRequest;
import com.projectcollab.dto.UpdateProgressResponse;
import com.projectcollab.entity.Progress;
import com.projectcollab.service.progress.ProgressService;
import com.projectcollab.service.task.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/progress")
public class ProgressController {

    @Autowired
    private ProgressService progressService;

    @Autowired
    private TaskService taskService;

    @PostMapping("/update")
    public ApiResponse<UpdateProgressResponse> updateProgress(@RequestBody UpdateProgressRequest request) {
        UpdateProgressResponse response = taskService.updateProgress(request);
        return ApiResponse.success(response);
    }

    @GetMapping("/project/{projectId}")
    public ApiResponse<List<Progress>> getProgressByProject(@PathVariable String projectId) {
        List<Progress> progressList = progressService.getProgressByProjectId(projectId);
        return ApiResponse.success(progressList);
    }
}
