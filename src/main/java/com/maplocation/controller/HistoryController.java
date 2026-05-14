package com.maplocation.controller;

import com.maplocation.dto.ApiResponse;
import com.maplocation.model.SearchHistory;
import com.maplocation.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping("/user/{userId}")
    public ApiResponse<List<SearchHistory>> getUserHistory(@PathVariable String userId) {
        List<SearchHistory> history = historyService.getUserHistory(userId);
        return ApiResponse.success(history);
    }

    @GetMapping("/user/{userId}/paged")
    public ApiResponse<Page<SearchHistory>> getUserHistoryPaged(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SearchHistory> historyPage = historyService.getUserHistoryPaged(userId, page, size);
        return ApiResponse.success(historyPage);
    }

    @GetMapping
    public ApiResponse<List<SearchHistory>> getAllHistory() {
        List<SearchHistory> history = historyService.getAllHistory();
        return ApiResponse.success(history);
    }
}
