package com.finance.controller;

import com.finance.dto.ApiResponse;
import com.finance.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping("/account/{accountId}")
    public ApiResponse<List<HistoryService.HistoryEntry>> getHistoryByAccount(@PathVariable String accountId) {
        List<HistoryService.HistoryEntry> history = historyService.getHistoryByAccount(accountId);
        return ApiResponse.success(history);
    }

    @GetMapping("/account/{accountId}/recent")
    public ApiResponse<List<HistoryService.HistoryEntry>> getRecentHistory(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "10") int limit) {
        List<HistoryService.HistoryEntry> history = historyService.getRecentHistory(accountId, limit);
        return ApiResponse.success(history);
    }
}
