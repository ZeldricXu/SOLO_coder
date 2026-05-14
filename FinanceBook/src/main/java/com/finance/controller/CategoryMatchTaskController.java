package com.finance.controller;

import com.finance.dto.ApiResponse;
import com.finance.entity.CategoryMatchTask;
import com.finance.service.CategoryMatchTaskService;
import com.finance.service.RedisQueueService;
import com.finance.worker.CategoryMatchWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/category-match-tasks")
@RequiredArgsConstructor
public class CategoryMatchTaskController {

    private final CategoryMatchTaskService taskService;
    private final RedisQueueService redisQueueService;
    private final CategoryMatchWorker worker;

    @GetMapping("/pending")
    public ApiResponse<List<CategoryMatchTask>> getPendingTasks() {
        List<CategoryMatchTask> tasks = taskService.getPendingTasks();
        return ApiResponse.success(tasks);
    }

    @GetMapping("/{taskId}")
    public ApiResponse<CategoryMatchTask> getTask(@PathVariable String taskId) {
        CategoryMatchTask task = taskService.getTaskById(taskId);
        return ApiResponse.success(task);
    }

    @GetMapping("/record/{recordId}")
    public ApiResponse<CategoryMatchTask> getTaskByRecord(@PathVariable String recordId) {
        return taskService.getTaskByRecordId(recordId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "任务不存在"));
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getTaskStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("pending_count", taskService.countPendingTasks());
        stats.put("completed_count", taskService.countCompletedTasks());
        stats.put("failed_count", taskService.countFailedTasks());
        stats.put("redis_queue_size", redisQueueService.getQueueSize(RedisQueueService.DEFAULT_QUEUE_KEY));
        stats.put("redis_dlq_size", redisQueueService.getDlqSize());
        stats.put("redis_available", redisQueueService.isRedisAvailable());
        stats.put("worker_running", worker.isRunning());
        stats.put("worker_count", worker.getWorkerCount());
        return ApiResponse.success(stats);
    }

    @PostMapping("/recover")
    public ApiResponse<Map<String, Object>> recoverTasks() {
        taskService.recoverPendingTasks();
        long pendingCount = taskService.countPendingTasks();

        Map<String, Object> result = new HashMap<>();
        result.put("recovered_pending", pendingCount);
        result.put("message", "任务恢复完成");
        return ApiResponse.success(result);
    }

    @PostMapping("/recover-dlq")
    public ApiResponse<Map<String, Object>> recoverFromDlq() {
        List<String> recovered = redisQueueService.recoverFromDlq();
        Map<String, Object> result = new HashMap<>();
        result.put("recovered_count", recovered.size());
        result.put("message", "死信队列恢复完成");
        return ApiResponse.success(result);
    }
}
