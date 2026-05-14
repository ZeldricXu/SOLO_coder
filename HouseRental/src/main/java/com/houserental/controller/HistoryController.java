package com.houserental.controller;

import com.houserental.dto.ApiResponse;
import com.houserental.entity.History;
import com.houserental.service.HistoryService;
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

    @GetMapping("/house/{houseId}")
    public ApiResponse<List<History>> getHouseHistory(@PathVariable String houseId) {
        List<History> history = historyService.getHouseHistory(houseId);
        return ApiResponse.success(history);
    }

    @GetMapping("/tenant/{tenantId}")
    public ApiResponse<List<History>> getTenantHistory(@PathVariable String tenantId) {
        List<History> history = historyService.getTenantHistory(tenantId);
        return ApiResponse.success(history);
    }

    @GetMapping("/landlord/{landlordId}")
    public ApiResponse<List<History>> getLandlordHistory(@PathVariable String landlordId) {
        List<History> history = historyService.getLandlordHistory(landlordId);
        return ApiResponse.success(history);
    }

    @GetMapping("/application/{applicationId}")
    public ApiResponse<List<History>> getApplicationHistory(@PathVariable String applicationId) {
        List<History> history = historyService.getApplicationHistory(applicationId);
        return ApiResponse.success(history);
    }

    @GetMapping("/contract/{contractId}")
    public ApiResponse<List<History>> getContractHistory(@PathVariable String contractId) {
        List<History> history = historyService.getContractHistory(contractId);
        return ApiResponse.success(history);
    }

    @GetMapping("/payment/{paymentId}")
    public ApiResponse<List<History>> getPaymentHistory(@PathVariable String paymentId) {
        List<History> history = historyService.getPaymentHistory(paymentId);
        return ApiResponse.success(history);
    }

    @GetMapping("/type/{historyType}")
    public ApiResponse<List<History>> getHistoryByType(@PathVariable String historyType) {
        List<History> history = historyService.getHistoryByType(historyType);
        return ApiResponse.success(history);
    }

    @GetMapping("/recent")
    public ApiResponse<List<History>> getRecentHistory(
            @RequestParam(defaultValue = "50") int limit) {
        List<History> history = historyService.getRecentHistory(limit);
        return ApiResponse.success(history);
    }

    @GetMapping("/range")
    public ApiResponse<List<History>> getHistoryByTimeRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        List<History> history = historyService.getHistoryByTimeRange(start, end);
        return ApiResponse.success(history);
    }

    @GetMapping("/list")
    public ApiResponse<List<History>> getAllHistory() {
        List<History> history = historyService.getAllHistory();
        return ApiResponse.success(history);
    }
}
