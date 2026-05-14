package com.projectcollab.controller;

import com.projectcollab.dto.ApiResponse;
import com.projectcollab.entity.HistoryRecord;
import com.projectcollab.service.history.HistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    @Autowired
    private HistoryService historyService;

    @GetMapping("/project/{projectId}")
    public ApiResponse<List<HistoryRecord>> getHistoryByProject(@PathVariable String projectId) {
        List<HistoryRecord> records = historyService.getHistoryByProjectId(projectId);
        return ApiResponse.success(records);
    }

    @GetMapping("/task/{taskId}")
    public ApiResponse<List<HistoryRecord>> getHistoryByTask(@PathVariable String taskId) {
        List<HistoryRecord> records = historyService.getHistoryByTaskId(taskId);
        return ApiResponse.success(records);
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<HistoryRecord>> getHistoryByUser(@PathVariable String userId) {
        List<HistoryRecord> records = historyService.getHistoryByUserId(userId);
        return ApiResponse.success(records);
    }
}
