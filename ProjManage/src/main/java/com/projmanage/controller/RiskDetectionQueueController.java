package com.projmanage.controller;

import com.projmanage.dto.ApiResponse;
import com.projmanage.dto.RiskDetectionTask;
import com.projmanage.service.RiskDetectionQueueService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/risk/detection-queue")
public class RiskDetectionQueueController {

    private final RiskDetectionQueueService riskDetectionQueueService;

    public RiskDetectionQueueController(RiskDetectionQueueService riskDetectionQueueService) {
        this.riskDetectionQueueService = riskDetectionQueueService;
    }

    @GetMapping("/size")
    public ApiResponse<Map<String, Object>> getQueueSize() {
        long pendingSize = riskDetectionQueueService.getQueueSize();
        List<RiskDetectionTask> processingTasks = riskDetectionQueueService.getProcessingTasks();
        List<RiskDetectionTask> failedTasks = riskDetectionQueueService.getFailedTasks();

        Map<String, Object> result = new HashMap<>();
        result.put("pending_tasks", pendingSize);
        result.put("processing_tasks", processingTasks.size());
        result.put("failed_tasks", failedTasks.size());
        result.put("total", pendingSize + processingTasks.size() + failedTasks.size());

        return ApiResponse.success(result);
    }

    @GetMapping("/pending")
    public ApiResponse<Map<String, Object>> getPendingTasks(
            @RequestParam(defaultValue = "10") int count) {
        List<RiskDetectionTask> tasks = riskDetectionQueueService.peekQueue(count);
        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        result.put("total", tasks.size());
        result.put("status", "pending");
        return ApiResponse.success(result);
    }

    @GetMapping("/processing")
    public ApiResponse<Map<String, Object>> getProcessingTasks() {
        List<RiskDetectionTask> tasks = riskDetectionQueueService.getProcessingTasks();
        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        result.put("total", tasks.size());
        result.put("status", "processing");
        return ApiResponse.success(result);
    }

    @GetMapping("/failed")
    public ApiResponse<Map<String, Object>> getFailedTasks() {
        List<RiskDetectionTask> tasks = riskDetectionQueueService.getFailedTasks();
        Map<String, Object> result = new HashMap<>();
        result.put("tasks", tasks);
        result.put("total", tasks.size());
        result.put("status", "failed");
        return ApiResponse.success(result);
    }

    @PostMapping("/retry-failed")
    public ApiResponse<Map<String, Object>> retryFailedTasks() {
        List<RiskDetectionTask> failedTasks = riskDetectionQueueService.getFailedTasks();
        int retryCount = failedTasks.size();
        riskDetectionQueueService.retryFailedTasks();

        Map<String, Object> result = new HashMap<>();
        result.put("message", "已将失败任务重新加入队列");
        result.put("retry_count", retryCount);
        return ApiResponse.success(result);
    }

    @PostMapping("/clear-all")
    public ApiResponse<Map<String, Object>> clearAllQueues() {
        long pendingSize = riskDetectionQueueService.getQueueSize();
        List<RiskDetectionTask> processingTasks = riskDetectionQueueService.getProcessingTasks();
        List<RiskDetectionTask> failedTasks = riskDetectionQueueService.getFailedTasks();
        int totalCleared = (int) (pendingSize + processingTasks.size() + failedTasks.size());

        riskDetectionQueueService.clearAllQueues();

        Map<String, Object> result = new HashMap<>();
        result.put("message", "所有风险检测队列已清空");
        result.put("cleared_count", totalCleared);
        return ApiResponse.success(result);
    }

    @PostMapping("/workers/start")
    public ApiResponse<Map<String, Object>> startWorkers() {
        riskDetectionQueueService.startWorkers();
        Map<String, Object> result = new HashMap<>();
        result.put("message", "风险检测Worker已启动");
        result.put("worker_count", 3);
        return ApiResponse.success(result);
    }

    @PostMapping("/workers/stop")
    public ApiResponse<Map<String, Object>> stopWorkers() {
        riskDetectionQueueService.stopWorkers();
        Map<String, Object> result = new HashMap<>();
        result.put("message", "风险检测Worker已停止");
        return ApiResponse.success(result);
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getQueueStatus() {
        Map<String, Object> result = new HashMap<>();

        long pendingSize = riskDetectionQueueService.getQueueSize();
        List<RiskDetectionTask> processingTasks = riskDetectionQueueService.getProcessingTasks();
        List<RiskDetectionTask> failedTasks = riskDetectionQueueService.getFailedTasks();

        result.put("queue_status", Map.of(
                "pending", pendingSize,
                "processing", processingTasks.size(),
                "failed", failedTasks.size()
        ));

        result.put("persistence", Map.of(
                "type", "Redis",
                "description", "风险检测任务持久化存储在Redis队列中，服务重启后不丢失"
        ));

        result.put("worker_info", Map.of(
                "worker_count", 3,
                "description", "3个Worker线程并行消费队列，支持高并发检测"
        ));

        result.put("advantages", new String[]{
                "服务重启后检测任务不丢失",
                "高并发检测支持",
                "失败任务可重试",
                "实时队列监控"
        });

        return ApiResponse.success(result);
    }
}
