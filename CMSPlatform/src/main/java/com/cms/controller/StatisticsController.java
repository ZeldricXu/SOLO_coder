package com.cms.controller;

import com.cms.dto.ApiResponse;
import com.cms.service.StatisticsQueueService;
import com.cms.service.StatisticsWorkerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    @Autowired
    private StatisticsQueueService statisticsQueueService;

    @Autowired
    private StatisticsWorkerService statisticsWorkerService;

    @GetMapping("/queue/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getQueueStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("pendingViewTasks", statisticsWorkerService.getPendingViewTasks());
        status.put("pendingLikeTasks", statisticsWorkerService.getPendingLikeTasks());
        status.put("pendingShareTasks", statisticsWorkerService.getPendingShareTasks());
        status.put("totalPendingTasks", statisticsWorkerService.getTotalPendingTasks());
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    @PostMapping("/queue/process")
    public ResponseEntity<ApiResponse<Void>> processQueue(@RequestParam(defaultValue = "all") String queueType) {
        if ("all".equals(queueType)) {
            statisticsWorkerService.processQueue("view");
            statisticsWorkerService.processQueue("like");
            statisticsWorkerService.processQueue("share");
        } else {
            statisticsWorkerService.processQueue(queueType);
        }
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/queue/clear")
    public ResponseEntity<ApiResponse<Void>> clearAllPendingTasks() {
        statisticsQueueService.clearAllPendingTasks();
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/view")
    public ResponseEntity<ApiResponse<Void>> recordView(@RequestParam String contentId) {
        String taskId = statisticsQueueService.enqueueViewTask(contentId, null, null);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/like")
    public ResponseEntity<ApiResponse<Void>> recordLike(@RequestParam String contentId) {
        String taskId = statisticsQueueService.enqueueLikeTask(contentId, null, null);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/share")
    public ResponseEntity<ApiResponse<Void>> recordShare(@RequestParam String contentId) {
        String taskId = statisticsQueueService.enqueueShareTask(contentId, null, null);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
