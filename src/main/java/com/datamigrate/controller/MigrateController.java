package com.datamigrate.controller;

import com.datamigrate.common.ApiResponse;
import com.datamigrate.dto.*;
import com.datamigrate.entity.MigrateLog;
import com.datamigrate.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/migrate")
@RequiredArgsConstructor
public class MigrateController {

    private final TaskService taskService;
    private final ProgressService progressService;
    private final VerifyService verifyService;
    private final MigrateService migrateService;
    private final LogService logService;
    private final HistoryService historyService;

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<CreateTaskResponse>> createTask(
            @Valid @RequestBody CreateTaskRequest request) {
        try {
            CreateTaskResponse response = taskService.createTask(request);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("创建迁移任务失败", e);
            return ResponseEntity.ok(ApiResponse.error("创建任务失败: " + e.getMessage()));
        }
    }

    @PostMapping("/start/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startTask(@PathVariable String taskId) {
        try {
            boolean started = migrateService.startMigrate(taskId);
            Map<String, Object> result = new HashMap<>();
            result.put("taskId", taskId);
            result.put("started", started);
            if (started) {
                result.put("message", "任务已启动");
            } else {
                result.put("message", "任务启动失败或已在运行中");
            }
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("启动任务失败: taskId={}", taskId, e);
            return ResponseEntity.ok(ApiResponse.error("启动任务失败: " + e.getMessage()));
        }
    }

    @PostMapping("/stop/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stopTask(@PathVariable String taskId) {
        try {
            boolean stopped = migrateService.stopMigrate(taskId);
            Map<String, Object> result = new HashMap<>();
            result.put("taskId", taskId);
            result.put("stopped", stopped);
            result.put("message", stopped ? "任务已停止" : "任务停止失败");
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("停止任务失败: taskId={}", taskId, e);
            return ResponseEntity.ok(ApiResponse.error("停止任务失败: " + e.getMessage()));
        }
    }

    @PostMapping("/pause/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pauseTask(@PathVariable String taskId) {
        try {
            boolean paused = taskService.pauseTask(taskId);
            Map<String, Object> result = new HashMap<>();
            result.put("taskId", taskId);
            result.put("paused", paused);
            result.put("message", paused ? "任务已暂停" : "任务暂停失败");
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("暂停任务失败: taskId={}", taskId, e);
            return ResponseEntity.ok(ApiResponse.error("暂停任务失败: " + e.getMessage()));
        }
    }

    @PostMapping("/resume/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resumeTask(@PathVariable String taskId) {
        try {
            boolean resumed = taskService.resumeTask(taskId);
            if (resumed) {
                migrateService.startMigrate(taskId);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("taskId", taskId);
            result.put("resumed", resumed);
            result.put("message", resumed ? "任务已恢复" : "任务恢复失败");
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("恢复任务失败: taskId={}", taskId, e);
            return ResponseEntity.ok(ApiResponse.error("恢复任务失败: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteTask(@PathVariable String taskId) {
        try {
            migrateService.stopMigrate(taskId);
            boolean deleted = taskService.deleteTask(taskId);
            Map<String, Object> result = new HashMap<>();
            result.put("taskId", taskId);
            result.put("deleted", deleted);
            result.put("message", deleted ? "任务已删除" : "任务删除失败");
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("删除任务失败: taskId={}", taskId, e);
            return ResponseEntity.ok(ApiResponse.error("删除任务失败: " + e.getMessage()));
        }
    }

    @GetMapping("/progress")
    public ResponseEntity<ApiResponse<ProgressResponse>> getProgress(
            @RequestParam String taskId) {
        try {
            ProgressResponse response = progressService.getProgressResponse(taskId);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("获取进度失败: taskId={}", taskId, e);
            return ResponseEntity.ok(ApiResponse.error("获取进度失败: " + e.getMessage()));
        }
    }

    @GetMapping("/verify")
    public ResponseEntity<ApiResponse<VerifyResponse>> getVerify(
            @RequestParam String taskId) {
        try {
            VerifyResponse response = verifyService.getLatestVerifyResult(taskId);
            if (response == null) {
                return ResponseEntity.ok(ApiResponse.success(new VerifyResponse()));
            }
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("获取校验结果失败: taskId={}", taskId, e);
            return ResponseEntity.ok(ApiResponse.error("获取校验结果失败: " + e.getMessage()));
        }
    }

    @PostMapping("/verify/{taskId}")
    public ResponseEntity<ApiResponse<VerifyResponse>> verify(@PathVariable String taskId) {
        try {
            VerifyResponse response = verifyService.verify(taskId);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("执行校验失败: taskId={}", taskId, e);
            return ResponseEntity.ok(ApiResponse.error("执行校验失败: " + e.getMessage()));
        }
    }

    @GetMapping("/task/{taskId}")
    public ResponseEntity<ApiResponse<TaskDetailResponse>> getTaskDetail(@PathVariable String taskId) {
        try {
            Optional<TaskDetailResponse> detail = taskService.getTaskDetail(taskId);
            return detail.map(taskDetailResponse -> ResponseEntity.ok(ApiResponse.success(taskDetailResponse)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.error(404, "任务不存在")));
        } catch (Exception e) {
            log.error("获取任务详情失败: taskId={}", taskId, e);
            return ResponseEntity.ok(ApiResponse.error("获取任务详情失败: " + e.getMessage()));
        }
    }

    @GetMapping("/tasks")
    public ResponseEntity<ApiResponse<TaskListResponse>> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        try {
            TaskListResponse response = taskService.listTasks(status, keyword, page, size);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("获取任务列表失败", e);
            return ResponseEntity.ok(ApiResponse.error("获取任务列表失败: " + e.getMessage()));
        }
    }

    @GetMapping("/logs/{taskId}")
    public ResponseEntity<ApiResponse<List<MigrateLog>>> getLogs(@PathVariable String taskId) {
        try {
            List<MigrateLog> logs = logService.getLogsByTaskId(taskId);
            return ResponseEntity.ok(ApiResponse.success(logs));
        } catch (Exception e) {
            log.error("获取日志失败: taskId={}", taskId, e);
            return ResponseEntity.ok(ApiResponse.error("获取日志失败: " + e.getMessage()));
        }
    }

    @GetMapping("/history/{taskId}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getHistory(@PathVariable String taskId) {
        try {
            List<Map<String, Object>> history = historyService.getTaskHistory(taskId);
            return ResponseEntity.ok(ApiResponse.success(history));
        } catch (Exception e) {
            log.error("获取历史记录失败: taskId={}", taskId, e);
            return ResponseEntity.ok(ApiResponse.error("获取历史记录失败: " + e.getMessage()));
        }
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getCompletedHistory() {
        try {
            List<Map<String, Object>> history = historyService.listCompletedTasks();
            return ResponseEntity.ok(ApiResponse.success(history));
        } catch (Exception e) {
            log.error("获取历史任务列表失败", e);
            return ResponseEntity.ok(ApiResponse.error("获取历史任务列表失败: " + e.getMessage()));
        }
    }

    @GetMapping("/statistics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatistics() {
        try {
            Map<String, Object> stats = historyService.getStatistics();
            return ResponseEntity.ok(ApiResponse.success(stats));
        } catch (Exception e) {
            log.error("获取统计数据失败", e);
            return ResponseEntity.ok(ApiResponse.error("获取统计数据失败: " + e.getMessage()));
        }
    }
}
