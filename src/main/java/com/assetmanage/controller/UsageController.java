package com.assetmanage.controller;

import com.assetmanage.dto.ApiResponse;
import com.assetmanage.dto.AssetReturnRequest;
import com.assetmanage.entity.UsageRecord;
import com.assetmanage.service.UsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageService usageService;

    @PostMapping("/return")
    public ApiResponse<Void> returnAsset(@RequestBody AssetReturnRequest request) {
        usageService.returnAsset(request);
        return ApiResponse.success();
    }

    @GetMapping("/asset/{assetId}")
    public ApiResponse<List<UsageRecord>> getUsageByAsset(@PathVariable String assetId) {
        List<UsageRecord> records = usageService.getUsageRecordsByAsset(assetId);
        return ApiResponse.success(records);
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<UsageRecord>> getUsageByUser(@PathVariable String userId) {
        List<UsageRecord> records = usageService.getUsageRecordsByUser(userId);
        return ApiResponse.success(records);
    }

    @GetMapping("/active")
    public ApiResponse<List<UsageRecord>> getActiveUsage() {
        List<UsageRecord> records = usageService.getActiveUsageRecords();
        return ApiResponse.success(records);
    }

    @GetMapping
    public ApiResponse<List<UsageRecord>> getAllUsage() {
        List<UsageRecord> records = usageService.getAllUsageRecords();
        return ApiResponse.success(records);
    }
}
