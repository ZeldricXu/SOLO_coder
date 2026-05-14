package com.fooddelivery.controller;

import com.fooddelivery.dto.ApiResponse;
import com.fooddelivery.entity.History;
import com.fooddelivery.service.HistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    @Autowired
    private HistoryService historyService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<History>>> getAllHistory() {
        List<History> history = historyService.getAllHistory();
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<ApiResponse<List<History>>> getHistoryByType(@PathVariable String type) {
        List<History> history = historyService.getHistoryByType(type);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/related/{relatedId}")
    public ResponseEntity<ApiResponse<List<History>>> getHistoryByRelatedId(@PathVariable String relatedId) {
        List<History> history = historyService.getHistoryByRelatedId(relatedId);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/type/{type}/related/{relatedId}")
    public ResponseEntity<ApiResponse<List<History>>> getHistoryByTypeAndRelatedId(@PathVariable String type,
                                                                                    @PathVariable String relatedId) {
        List<History> history = historyService.getHistoryByTypeAndRelatedId(type, relatedId);
        return ResponseEntity.ok(ApiResponse.success(history));
    }
}
