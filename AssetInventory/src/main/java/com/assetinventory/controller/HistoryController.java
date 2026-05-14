package com.assetinventory.controller;

import com.assetinventory.dto.ApiResponse;
import com.assetinventory.entity.InventoryHistory;
import com.assetinventory.service.HistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    private final HistoryService historyService;

    @Autowired
    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<InventoryHistory>>> getAllHistory() {
        List<InventoryHistory> history = historyService.getAllHistory();
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/type/{historyType}")
    public ResponseEntity<ApiResponse<List<InventoryHistory>>> getHistoryByType(@PathVariable String historyType) {
        List<InventoryHistory> history = historyService.getHistoryByType(historyType);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/reference/{referenceId}")
    public ResponseEntity<ApiResponse<List<InventoryHistory>>> getHistoryByReferenceId(@PathVariable String referenceId) {
        List<InventoryHistory> history = historyService.getHistoryByReferenceId(referenceId);
        return ResponseEntity.ok(ApiResponse.success(history));
    }
}
