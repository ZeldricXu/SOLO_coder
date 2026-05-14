package com.library.librarymgmt.controller;

import com.library.librarymgmt.dto.ApiResponse;
import com.library.librarymgmt.entity.HistoryLog;
import com.library.librarymgmt.service.HistoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping
    public ApiResponse<List<HistoryLog>> getAllLogs() {
        return ApiResponse.success(historyService.getAllLogs());
    }

    @GetMapping("/type/{logType}")
    public ApiResponse<List<HistoryLog>> getLogsByType(@PathVariable String logType) {
        return ApiResponse.success(historyService.getLogsByType(logType));
    }

    @GetMapping("/ref/{refId}")
    public ApiResponse<List<HistoryLog>> getLogsByRefId(@PathVariable String refId) {
        return ApiResponse.success(historyService.getLogsByRefId(refId));
    }

    @GetMapping("/book/{bookId}")
    public ApiResponse<List<HistoryLog>> getLogsByBookId(@PathVariable String bookId) {
        return ApiResponse.success(historyService.getLogsByBookId(bookId));
    }

    @GetMapping("/reader/{readerId}")
    public ApiResponse<List<HistoryLog>> getLogsByReaderId(@PathVariable String readerId) {
        return ApiResponse.success(historyService.getLogsByReaderId(readerId));
    }
}
