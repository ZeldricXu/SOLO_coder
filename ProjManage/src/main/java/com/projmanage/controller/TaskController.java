package com.projmanage.controller;

import com.projmanage.dto.ApiResponse;
import com.projmanage.dto.CreateTaskRequest;
import com.projmanage.model.Task;
import com.projmanage.service.TaskService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/create")
    public ApiResponse<Map<String, String>> createTask(@RequestBody CreateTaskRequest request) {
        String taskId = taskService.createTask(request);
        Map<String, String> result = new HashMap<>();
        result.put("task_id", taskId);
        return ApiResponse.success(result);
    }

    @GetMapping("/{taskId}")
    public ApiResponse<Task> getTaskById(@PathVariable String taskId) {
        Optional<Task> taskOpt = taskService.getTaskById(taskId);
        if (taskOpt.isPresent()) {
            return ApiResponse.success(taskOpt.get());
        }
        return ApiResponse.error(404, "任务不存在");
    }

    @GetMapping
    public ApiResponse<List<Task>> getTasksByProject(@RequestParam(required = false) String projectId) {
        if (projectId != null && !projectId.isEmpty()) {
            return ApiResponse.success(taskService.getTasksByProjectId(projectId));
        }
        return ApiResponse.error(400, "请提供项目ID");
    }

    @GetMapping("/assignee/{assigneeId}")
    public ApiResponse<List<Task>> getTasksByAssignee(@PathVariable String assigneeId) {
        return ApiResponse.success(taskService.getTasksByAssignee(assigneeId));
    }

    @PutMapping("/{taskId}/progress")
    public ApiResponse<Void> updateTaskProgress(@PathVariable String taskId,
                                                @RequestParam Integer progress,
                                                @RequestParam(required = false) Integer actualHours) {
        taskService.updateTaskProgress(taskId, progress, actualHours);
        return ApiResponse.success(null);
    }

    @PutMapping("/{taskId}/status")
    public ApiResponse<Void> updateTaskStatus(@PathVariable String taskId, @RequestParam String status) {
        taskService.updateTaskStatus(taskId, status);
        return ApiResponse.success(null);
    }

    @PostMapping("/{taskId}/assign")
    public ApiResponse<Void> assignTask(@PathVariable String taskId, @RequestParam String assigneeId) {
        taskService.assignTask(taskId, assigneeId);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{taskId}")
    public ApiResponse<Void> deleteTask(@PathVariable String taskId) {
        taskService.deleteTask(taskId);
        return ApiResponse.success(null);
    }
}
