package com.parking.platform.core.controller;

import com.parking.platform.common.constant.Constants;
import com.parking.platform.common.dto.ApiResponse;
import com.parking.platform.common.dto.PagedResponse;
import com.parking.platform.core.entity.Task;
import com.parking.platform.core.service.TaskService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ApiResponse<Task> createTask(@Valid @RequestBody Task task) {
        Task created = taskService.submitTask(task);
        return ApiResponse.created(created);
    }

    @GetMapping("/{id}")
    public ApiResponse<Task> getTask(@PathVariable String id) {
        Task task = taskService.getTask(id);
        return ApiResponse.success(task);
    }

    @GetMapping("/{id}/status")
    public ApiResponse<Map<String, Object>> getTaskStatus(@PathVariable String id) {
        Task task = taskService.getTask(id);
        Map<String, Object> status = new HashMap<>();
        status.put("id", task.getId());
        status.put("status", task.getStatus());
        status.put("phase", task.getPhase());
        status.put("progress", task.getProgress());
        status.put("startedAt", task.getStartedAt());
        status.put("completedAt", task.getCompletedAt());
        status.put("errorDetail", task.getErrorDetail());
        return ApiResponse.success(status);
    }

    @GetMapping
    public ApiResponse<PagedResponse<Task>> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {

        List<Task> tasks = taskService.listTasks(status, type, page, size);
        long total = taskService.countTasks(status, type);

        PagedResponse<Task> response = PagedResponse.of(tasks, page, size, total);
        return ApiResponse.success(response);
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Task> cancelTask(@PathVariable String id) {
        Task task = taskService.cancelTask(id);
        return ApiResponse.success(task);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteTask(@PathVariable String id) {
        boolean deleted = taskService.deleteTask(id);
        if (!deleted) {
            return ApiResponse.notFound("Task not found");
        }
        return ApiResponse.noContent();
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> getStatistics() {
        Map<String, Long> stats = taskService.getTaskStatistics();
        return ApiResponse.success(stats);
    }

    @PostMapping("/batch")
    public ApiResponse<Map<String, Object>> batchOperations(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) request.get("operations");

        String batchId = "batch_" + System.currentTimeMillis();
        List<Map<String, Object>> results = new java.util.ArrayList<>();

        for (Map<String, Object> op : operations) {
            Map<String, Object> result = new HashMap<>();
            try {
                String action = (String) op.get("action");
                String id = (String) op.get("id");

                result.put("id", id);
                result.put("action", action);

                if ("cancel".equals(action) && id != null) {
                    taskService.cancelTask(id);
                    result.put("success", true);
                } else if ("delete".equals(action) && id != null) {
                    taskService.deleteTask(id);
                    result.put("success", true);
                } else {
                    result.put("success", false);
                    result.put("error", "Unknown action or missing id");
                }
            } catch (Exception e) {
                result.put("success", false);
                result.put("error", e.getMessage());
            }
            results.add(result);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("batchId", batchId);
        response.put("results", results);

        return ApiResponse.success(response);
    }
}
