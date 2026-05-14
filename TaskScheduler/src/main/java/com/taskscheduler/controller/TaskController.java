package com.taskscheduler.controller;

import com.taskscheduler.dto.*;
import com.taskscheduler.entity.ExecuteRecord;
import com.taskscheduler.entity.TaskConfig;
import com.taskscheduler.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final DispatcherService dispatcherService;
    private final MonitorService monitorService;
    private final LogService logService;
    private final DependencyService dependencyService;
    private final ExecutorManagerService executorManagerService;

    @PostMapping("/tasks/create")
    public ResponseEntity<ApiResponse<Map<String, String>>> createTask(@RequestBody CreateTaskRequest request) {
        try {
            TaskConfig task = taskService.createTask(request);
            Map<String, String> result = new HashMap<>();
            result.put("task_id", task.getTaskId());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("Failed to create task: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/tasks/{taskId}")
    public ResponseEntity<ApiResponse<TaskConfig>> updateTask(
            @PathVariable String taskId,
            @RequestBody UpdateTaskRequest request) {
        try {
            TaskConfig task = taskService.updateTask(taskId, request);
            return ResponseEntity.ok(ApiResponse.success(task));
        } catch (Exception e) {
            log.error("Failed to update task: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<ApiResponse<Void>> deleteTask(@PathVariable String taskId) {
        try {
            taskService.deleteTask(taskId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            log.error("Failed to delete task: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<ApiResponse<TaskConfig>> getTask(@PathVariable String taskId) {
        return taskService.getTask(taskId)
                .map(task -> ResponseEntity.ok(ApiResponse.success(task)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks")
    public ResponseEntity<ApiResponse<List<TaskConfig>>> getAllTasks() {
        return ResponseEntity.ok(ApiResponse.success(taskService.getAllTasks()));
    }

    @PostMapping("/tasks/trigger")
    public ResponseEntity<ApiResponse<Map<String, String>>> triggerTask(@RequestBody TriggerTaskRequest request) {
        try {
            ExecuteRecord record = dispatcherService.triggerAndDispatch(request.getTaskId(), request.getTriggerType());
            Map<String, String> result = new HashMap<>();
            result.put("execute_id", record.getExecuteId());
            result.put("status", record.getExecuteStatus());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("Failed to trigger task: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/tasks/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTaskStatus(
            @RequestParam String taskId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        try {
            List<ExecuteRecord> executions;
            if (startTime != null && endTime != null) {
                executions = monitorService.getTaskExecutionsByTimeRange(taskId, startTime, endTime);
            } else {
                executions = monitorService.getTaskExecutions(taskId);
            }

            TaskStatistics stats = monitorService.getTaskStatistics(taskId);

            Map<String, Object> result = new HashMap<>();
            result.put("executions", executions);
            result.put("statistics", stats);

            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("Failed to get task status: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/tasks/{taskId}/logs")
    public ResponseEntity<ApiResponse<Object>> getTaskLogs(@PathVariable String taskId) {
        return ResponseEntity.ok(ApiResponse.success(logService.getLogsByTaskId(taskId)));
    }

    @GetMapping("/executions/{executeId}/logs")
    public ResponseEntity<ApiResponse<Object>> getExecutionLogs(@PathVariable String executeId) {
        return ResponseEntity.ok(ApiResponse.success(logService.getLogsByExecuteId(executeId)));
    }

    @PostMapping("/executors/register")
    public ResponseEntity<ApiResponse<Object>> registerExecutor(@RequestBody RegisterExecutorRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(executorManagerService.registerExecutor(request)));
        } catch (Exception e) {
            log.error("Failed to register executor: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/executors/{executorId}/heartbeat")
    public ResponseEntity<ApiResponse<Void>> executorHeartbeat(@PathVariable String executorId) {
        executorManagerService.heartbeat(executorId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/executors")
    public ResponseEntity<ApiResponse<List<com.taskscheduler.entity.Executor>>> getAllExecutors() {
        return ResponseEntity.ok(ApiResponse.success(executorManagerService.getAllExecutors()));
    }

    @GetMapping("/executors/online")
    public ResponseEntity<ApiResponse<List<com.taskscheduler.entity.Executor>>> getOnlineExecutors() {
        return ResponseEntity.ok(ApiResponse.success(executorManagerService.getOnlineExecutors()));
    }

    @PostMapping("/tasks/{taskId}/enable")
    public ResponseEntity<ApiResponse<Void>> enableTask(@PathVariable String taskId) {
        try {
            taskService.enableTask(taskId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            log.error("Failed to enable task: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/tasks/{taskId}/disable")
    public ResponseEntity<ApiResponse<Void>> disableTask(@PathVariable String taskId) {
        try {
            taskService.disableTask(taskId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            log.error("Failed to disable task: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/system/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSystemStatus() {
        return ResponseEntity.ok(ApiResponse.success(monitorService.getSystemStatus()));
    }

    @GetMapping("/tasks/running")
    public ResponseEntity<ApiResponse<List<ExecuteRecord>>> getRunningTasks() {
        return ResponseEntity.ok(ApiResponse.success(monitorService.getRunningTasks()));
    }
}
