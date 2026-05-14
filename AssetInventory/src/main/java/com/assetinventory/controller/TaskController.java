package com.assetinventory.controller;

import com.assetinventory.dto.ApiResponse;
import com.assetinventory.dto.CreateTaskRequest;
import com.assetinventory.entity.InventoryTask;
import com.assetinventory.service.TaskService;
import com.assetinventory.util.TaskLockManager.TaskLock;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    @Autowired
    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<Map<String, String>>> createTask(
            @Valid @RequestBody CreateTaskRequest request) {
        InventoryTask task = taskService.createTask(
                request.getPlanId(),
                request.getTaskRange(),
                request.getTaskPriority()
        );

        Map<String, String> data = new HashMap<>();
        data.put("task_id", task.getTaskId());
        data.put("status", task.getTaskStatus());
        data.put("priority", task.getTaskPriority());

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<InventoryTask>>> getAllTasks() {
        List<InventoryTask> tasks = taskService.getAllTasks();
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<InventoryTask>>> getTasksByStatus(@PathVariable String status) {
        List<InventoryTask> tasks = taskService.getTasksByStatus(status);
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    @GetMapping("/priority/{priority}")
    public ResponseEntity<ApiResponse<List<InventoryTask>>> getTasksByPriority(@PathVariable String priority) {
        List<InventoryTask> tasks = taskService.getTasksByPriority(priority);
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    @GetMapping("/priorities")
    public ResponseEntity<ApiResponse<List<String>>> getAvailablePriorities() {
        List<String> priorities = taskService.getAvailablePriorities();
        return ResponseEntity.ok(ApiResponse.success(priorities));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<InventoryTask>> getTaskById(@PathVariable String taskId) {
        return taskService.getTaskById(taskId)
                .map(task -> ResponseEntity.ok(ApiResponse.success(task)))
                .orElse(ResponseEntity.ok(ApiResponse.error(404, "任务不存在")));
    }

    @GetMapping("/{taskId}/lock-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTaskLockStatus(@PathVariable String taskId) {
        Map<String, Object> data = new HashMap<>();
        data.put("task_id", taskId);
        data.put("locked", taskService.isTaskLocked(taskId));

        TaskLock currentLock = taskService.getCurrentTaskLock(taskId);
        if (currentLock != null) {
            data.put("holder", currentLock.getHolder());
            data.put("priority", currentLock.getPriorityName());
            data.put("remaining_time_ms", currentLock.getRemainingTime());
            data.put("timeout_ms", currentLock.getTimeoutMs());
        }

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/{taskId}/lock")
    public ResponseEntity<ApiResponse<Map<String, Object>>> lockTask(
            @PathVariable String taskId,
            @RequestParam(required = false, defaultValue = "api") String holder) {
        TaskLock lock = taskService.acquireTaskLock(taskId, holder);

        if (lock == null) {
            return ResponseEntity.ok(ApiResponse.error(409, "任务已被锁定，无法获取锁"));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("task_id", taskId);
        data.put("locked", true);
        data.put("holder", lock.getHolder());
        data.put("priority", lock.getPriorityName());
        data.put("timeout_ms", lock.getTimeoutMs());
        data.put("remaining_time_ms", lock.getRemainingTime());

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/{taskId}/unlock")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unlockTask(@PathVariable String taskId) {
        TaskLock currentLock = taskService.getCurrentTaskLock(taskId);

        if (currentLock == null) {
            return ResponseEntity.ok(ApiResponse.error(404, "任务当前未锁定"));
        }

        boolean released = taskService.releaseTaskLock(currentLock);

        Map<String, Object> data = new HashMap<>();
        data.put("task_id", taskId);
        data.put("released", released);

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/locks/active-count")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> getActiveLockCount() {
        Map<String, Integer> data = new HashMap<>();
        data.put("active_lock_count", taskService.getActiveLockCount());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/locks/clear-all")
    public ResponseEntity<ApiResponse<Map<String, String>>> clearAllLocks() {
        taskService.clearAllTaskLocks();
        Map<String, String> data = new HashMap<>();
        data.put("status", "cleared");
        data.put("message", "所有任务锁已清除");
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
