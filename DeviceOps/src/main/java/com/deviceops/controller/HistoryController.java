package com.deviceops.controller;

import com.deviceops.dto.ApiResponse;
import com.deviceops.entity.OperationHistory;
import com.deviceops.service.history.HistoryService;
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
    public ApiResponse<List<OperationHistory>> getAllHistory() {
        return ApiResponse.success(historyService.getAllHistory());
    }

    @GetMapping("/device/{deviceId}")
    public ApiResponse<List<OperationHistory>> getHistoryByDevice(@PathVariable String deviceId) {
        return ApiResponse.success(historyService.getHistoryByDevice(deviceId));
    }

    @GetMapping("/operator/{operatorId}")
    public ApiResponse<List<OperationHistory>> getHistoryByOperator(@PathVariable String operatorId) {
        return ApiResponse.success(historyService.getHistoryByOperator(operatorId));
    }

    @GetMapping("/task/{taskId}")
    public ApiResponse<List<OperationHistory>> getHistoryByTask(@PathVariable String taskId) {
        return ApiResponse.success(historyService.getHistoryByTask(taskId));
    }

    @GetMapping("/fault/{faultId}")
    public ApiResponse<List<OperationHistory>> getHistoryByFault(@PathVariable String faultId) {
        return ApiResponse.success(historyService.getHistoryByFault(faultId));
    }

    @GetMapping("/type/{operationType}")
    public ApiResponse<List<OperationHistory>> getHistoryByType(@PathVariable String operationType) {
        return ApiResponse.success(historyService.getHistoryByType(operationType));
    }

    @GetMapping("/range")
    public ApiResponse<List<OperationHistory>> getHistoryByTimeRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ApiResponse.success(historyService.getHistoryByTimeRange(start, end));
    }
}
