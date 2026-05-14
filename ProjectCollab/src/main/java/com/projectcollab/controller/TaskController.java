package com.projectcollab.controller;

import com.projectcollab.dto.ApiResponse;
import com.projectcollab.dto.CreateTaskRequest;
import com.projectcollab.dto.CreateTaskResponse;
import com.projectcollab.entity.Task;
import com.projectcollab.service.task.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    @Autowired
    private TaskService taskService;

    @PostMapping("/create")
    public ApiResponse<CreateTaskResponse> createTask(@RequestBody CreateTaskRequest request) {
        CreateTaskResponse response = taskService.createTask(request);
        return ApiResponse.success(response);
    }

    @GetMapping("/{taskId}")
    public ApiResponse<Task> getTask(@PathVariable String taskId) {
        return taskService.getTaskById(taskId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "任务不存在"));
    }

    @GetMapping("/project/{projectId}")
    public ApiResponse<List<Task>> getTasksByProject(@PathVariable String projectId) {
        List<Task> tasks = taskService.getTasksByProjectId(projectId);
        return ApiResponse.success(tasks);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<Task>> getTasksByStatus(@PathVariable String status) {
        List<Task> tasks = taskService.getTasksByStatus(status);
        return ApiResponse.success(tasks);
    }

    @PostMapping("/{taskId}/start")
    public ApiResponse<Task> startTask(@PathVariable String taskId) {
        Task task = taskService.startTask(taskId);
        return ApiResponse.success(task);
    }
}
