package com.cms.controller;

import com.cms.dto.ApiResponse;
import com.cms.entity.HistoryRecord;
import com.cms.service.HistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    @Autowired
    private HistoryService historyService;

    @GetMapping("/content/{contentId}")
    public ApiResponse<List<HistoryRecord>> getContentHistory(@PathVariable String contentId) {
        List<HistoryRecord> history = historyService.getContentHistory(contentId);
        return ApiResponse.success(history);
    }

    @GetMapping("/content/{contentId}/type/{operationType}")
    public ApiResponse<List<HistoryRecord>> getContentHistoryByOperationType(
            @PathVariable String contentId,
            @PathVariable String operationType) {
        List<HistoryRecord> history = historyService.getContentHistoryByOperationType(contentId, operationType);
        return ApiResponse.success(history);
    }

    @GetMapping("/operator/{operatorId}")
    public ApiResponse<List<HistoryRecord>> getOperatorHistory(@PathVariable String operatorId) {
        List<HistoryRecord> history = historyService.getOperatorHistory(operatorId);
        return ApiResponse.success(history);
    }

    @GetMapping("/type/{operationType}")
    public ApiResponse<List<HistoryRecord>> getHistoryByOperationType(@PathVariable String operationType) {
        List<HistoryRecord> history = historyService.getHistoryByOperationType(operationType);
        return ApiResponse.success(history);
    }

    @GetMapping("/{historyId}")
    public ApiResponse<HistoryRecord> getHistory(@PathVariable String historyId) {
        HistoryRecord history = historyService.getHistoryById(historyId);
        return ApiResponse.success(history);
    }
}
