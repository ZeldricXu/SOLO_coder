package com.assetinventory.controller;

import com.assetinventory.dto.ApiResponse;
import com.assetinventory.dto.ExecuteCountingRequest;
import com.assetinventory.entity.InventoryRecord;
import com.assetinventory.service.CountingService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/countings")
public class CountingController {

    private final CountingService countingService;

    @Autowired
    public CountingController(CountingService countingService) {
        this.countingService = countingService;
    }

    @PostMapping("/execute")
    public ResponseEntity<ApiResponse<Map<String, String>>> executeCounting(
            @Valid @RequestBody ExecuteCountingRequest request) {
        InventoryRecord record = countingService.executeCounting(
                request.getTaskId(),
                request.getAssetId(),
                request.getCountQuantity(),
                request.getCountLocation()
        );

        Map<String, String> data = new HashMap<>();
        data.put("count_id", record.getCountId());
        data.put("status", record.getCountStatus());

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<InventoryRecord>>> getAllRecords() {
        List<InventoryRecord> records = countingService.getAllRecords();
        return ResponseEntity.ok(ApiResponse.success(records));
    }

    @GetMapping("/task/{taskId}")
    public ResponseEntity<ApiResponse<List<InventoryRecord>>> getRecordsByTaskId(@PathVariable String taskId) {
        List<InventoryRecord> records = countingService.getRecordsByTaskId(taskId);
        return ResponseEntity.ok(ApiResponse.success(records));
    }

    @GetMapping("/asset/{assetId}")
    public ResponseEntity<ApiResponse<List<InventoryRecord>>> getRecordsByAssetId(@PathVariable String assetId) {
        List<InventoryRecord> records = countingService.getRecordsByAssetId(assetId);
        return ResponseEntity.ok(ApiResponse.success(records));
    }

    @GetMapping("/{countId}")
    public ResponseEntity<ApiResponse<InventoryRecord>> getRecordById(@PathVariable String countId) {
        return countingService.getRecordById(countId)
                .map(record -> ResponseEntity.ok(ApiResponse.success(record)))
                .orElse(ResponseEntity.ok(ApiResponse.error(404, "盘点记录不存在")));
    }
}
