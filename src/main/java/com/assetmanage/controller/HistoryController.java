package com.assetmanage.controller;

import com.assetmanage.dto.ApiResponse;
import com.assetmanage.entity.AssetHistory;
import com.assetmanage.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping("/asset/{assetId}")
    public ApiResponse<List<AssetHistory>> getAssetHistory(@PathVariable String assetId) {
        List<AssetHistory> history = historyService.getAssetHistory(assetId);
        return ApiResponse.success(history);
    }

    @GetMapping("/action/{actionType}")
    public ApiResponse<List<AssetHistory>> getHistoryByAction(@PathVariable String actionType) {
        List<AssetHistory> history = historyService.getHistoryByActionType(actionType);
        return ApiResponse.success(history);
    }

    @GetMapping("/operator/{operatorId}")
    public ApiResponse<List<AssetHistory>> getHistoryByOperator(@PathVariable String operatorId) {
        List<AssetHistory> history = historyService.getHistoryByOperator(operatorId);
        return ApiResponse.success(history);
    }

    @GetMapping
    public ApiResponse<List<AssetHistory>> getAllHistory() {
        List<AssetHistory> history = historyService.getAllHistory();
        return ApiResponse.success(history);
    }
}
