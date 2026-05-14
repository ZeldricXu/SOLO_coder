package com.logistics.controller;

import com.logistics.dto.ApiResponse;
import com.logistics.entity.LogisticsHistory;
import com.logistics.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping("/logistics/{logisticsId}")
    public ApiResponse<List<LogisticsHistory>> getHistoryByLogisticsId(@PathVariable String logisticsId) {
        List<LogisticsHistory> historyList = historyService.getHistoryByLogisticsId(logisticsId);
        return ApiResponse.success(historyList);
    }

    @GetMapping("/type/{historyType}")
    public ApiResponse<List<LogisticsHistory>> getHistoryByType(@PathVariable String historyType) {
        List<LogisticsHistory> historyList = historyService.getHistoryByType(historyType);
        return ApiResponse.success(historyList);
    }

    @GetMapping("/list")
    public ApiResponse<List<LogisticsHistory>> getAllHistory() {
        List<LogisticsHistory> historyList = historyService.getAllHistory();
        return ApiResponse.success(historyList);
    }
}
