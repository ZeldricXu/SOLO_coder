package com.restaurant.mgmt.controller;

import com.restaurant.mgmt.dto.ApiResponse;
import com.restaurant.mgmt.model.HistoryRecord;
import com.restaurant.mgmt.service.HistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    @Autowired
    private HistoryService historyService;

    @GetMapping
    public ApiResponse<List<HistoryRecord>> getAllHistory() {
        List<HistoryRecord> records = historyService.getAllHistory();
        return ApiResponse.success(records);
    }

    @GetMapping("/{historyId}")
    public ApiResponse<HistoryRecord> getHistory(@PathVariable String historyId) {
        HistoryRecord record = historyService.getHistoryById(historyId);
        return ApiResponse.success(record);
    }

    @GetMapping("/type/{recordType}")
    public ApiResponse<List<HistoryRecord>> getHistoryByType(@PathVariable String recordType) {
        List<HistoryRecord> records = historyService.getHistoryByType(recordType);
        return ApiResponse.success(records);
    }

    @GetMapping("/reference/{referenceId}")
    public ApiResponse<List<HistoryRecord>> getHistoryByReferenceId(@PathVariable String referenceId) {
        List<HistoryRecord> records = historyService.getHistoryByReferenceId(referenceId);
        return ApiResponse.success(records);
    }

    @GetMapping("/range")
    public ApiResponse<List<HistoryRecord>> getHistoryByTimeRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        List<HistoryRecord> records = historyService.getHistoryByTimeRange(startTime, endTime);
        return ApiResponse.success(records);
    }

    @GetMapping("/type/{recordType}/range")
    public ApiResponse<List<HistoryRecord>> getHistoryByTypeAndTimeRange(
            @PathVariable String recordType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        List<HistoryRecord> records = historyService.getHistoryByTypeAndTimeRange(recordType, startTime, endTime);
        return ApiResponse.success(records);
    }

    @GetMapping("/order/{orderId}")
    public ApiResponse<List<HistoryRecord>> getOrderHistory(@PathVariable String orderId) {
        List<HistoryRecord> records = historyService.getOrderHistory(orderId);
        return ApiResponse.success(records);
    }

    @GetMapping("/stock/{stockId}")
    public ApiResponse<List<HistoryRecord>> getStockHistory(@PathVariable String stockId) {
        List<HistoryRecord> records = historyService.getStockHistory(stockId);
        return ApiResponse.success(records);
    }

    @GetMapping("/table/{tableId}")
    public ApiResponse<List<HistoryRecord>> getTableHistory(@PathVariable String tableId) {
        List<HistoryRecord> records = historyService.getTableHistory(tableId);
        return ApiResponse.success(records);
    }

    @GetMapping("/orders/range")
    public ApiResponse<List<HistoryRecord>> getOrderHistoryByTimeRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        List<HistoryRecord> records = historyService.getOrderHistoryByTimeRange(startTime, endTime);
        return ApiResponse.success(records);
    }

    @GetMapping("/stocks/range")
    public ApiResponse<List<HistoryRecord>> getStockHistoryByTimeRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        List<HistoryRecord> records = historyService.getStockHistoryByTimeRange(startTime, endTime);
        return ApiResponse.success(records);
    }
}
