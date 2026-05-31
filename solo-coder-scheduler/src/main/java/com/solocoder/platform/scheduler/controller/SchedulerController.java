package com.solocoder.platform.scheduler.controller;

import com.solocoder.platform.common.model.ApiResponse;
import com.solocoder.platform.scheduler.model.TaskDefinition;
import com.solocoder.platform.scheduler.model.TaskExecution;
import com.solocoder.platform.scheduler.service.TaskSchedulingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/scheduler")
@RequiredArgsConstructor
public class SchedulerController {

    private final TaskSchedulingService taskSchedulingService;

    @PostMapping("/tasks")
    public ApiResponse<TaskDefinition> createTask(@Valid @RequestBody TaskDefinition task) {
        return ApiResponse.success(taskSchedulingService.createTask(task));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<TaskDefinition> getTask(@PathVariable String taskId) {
        return taskSchedulingService.getTask(taskId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Task not found: " + taskId));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<TaskDefinition>> listTasks() {
        return ApiResponse.success(taskSchedulingService.listTasks());
    }

    @DeleteMapping("/tasks/{taskId}")
    public ApiResponse<Void> deleteTask(@PathVariable String taskId) {
        taskSchedulingService.deleteTask(taskId);
        return ApiResponse.success();
    }

    @PostMapping("/tasks/{taskId}/execute")
    public ApiResponse<TaskExecution> executeTask(@PathVariable String taskId) {
        return ApiResponse.success(taskSchedulingService.executeTask(taskId));
    }

    @GetMapping("/executions/{executionId}")
    public ApiResponse<TaskExecution> getExecution(@PathVariable String executionId) {
        TaskExecution execution = taskSchedulingService.getExecution(executionId);
        if (execution == null) {
            return ApiResponse.error(404, "Execution not found: " + executionId);
        }
        return ApiResponse.success(execution);
    }

    @GetMapping("/tasks/{taskId}/executions")
    public ApiResponse<List<TaskExecution>> getTaskExecutions(@PathVariable String taskId) {
        return ApiResponse.success(taskSchedulingService.getTaskExecutions(taskId));
    }

    @GetMapping("/executions/running")
    public ApiResponse<List<TaskExecution>> getRunningExecutions() {
        return ApiResponse.success(taskSchedulingService.getRunningExecutions());
    }

    @PostMapping("/executions/{executionId}/cancel")
    public ApiResponse<Void> cancelExecution(@PathVariable String executionId) {
        taskSchedulingService.cancelExecution(executionId);
        return ApiResponse.success();
    }
}
