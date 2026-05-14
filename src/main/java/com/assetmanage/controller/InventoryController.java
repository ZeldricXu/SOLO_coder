package com.assetmanage.controller;

import com.assetmanage.dto.ApiResponse;
import com.assetmanage.dto.InventoryDiffHandleRequest;
import com.assetmanage.entity.InventoryCheck;
import com.assetmanage.entity.InventoryDifference;
import com.assetmanage.service.InventoryAsyncService;
import com.assetmanage.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final InventoryAsyncService asyncService;

    @PostMapping("/check")
    public ApiResponse<String> createCheck(
            @RequestParam String type,
            @RequestParam(required = false) String department,
            @RequestParam(defaultValue = "0") int totalAssets) {
        String checkId = inventoryService.createInventoryCheck(type, department, totalAssets);
        return ApiResponse.success(checkId);
    }

    @PostMapping("/difference")
    public ApiResponse<String> createDifference(
            @RequestParam String checkId,
            @RequestParam String assetId,
            @RequestParam(required = false) String systemLocation,
            @RequestParam(required = false) String actualLocation,
            @RequestParam String diffType) {
        String diffId = inventoryService.createDifference(checkId, assetId, systemLocation, actualLocation, diffType);
        return ApiResponse.success(diffId);
    }

    @PostMapping("/handle")
    public ApiResponse<Void> handleDifference(@RequestBody InventoryDiffHandleRequest request) {
        inventoryService.handleDifference(request);
        return ApiResponse.success();
    }

    @PostMapping("/report/submit")
    public ApiResponse<Map<String, Object>> submitInventoryReportAsync(
            @RequestParam String checkId,
            @RequestParam(required = false) List<String> diffIds,
            @RequestParam(required = false) String operatorId) {
        
        if (diffIds == null || diffIds.isEmpty()) {
            List<InventoryDifference> pendingDiffs = inventoryService.getPendingDifferences();
            diffIds = pendingDiffs.stream()
                    .filter(d -> checkId.equals(d.getCheckId()))
                    .map(InventoryDifference::getDiffId)
                    .toList();
        }

        String taskId = asyncService.submitAsyncProcessing(checkId, diffIds, operatorId);

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("checkId", checkId);
        result.put("diffCount", diffIds.size());
        result.put("status", "submitted");
        result.put("message", "盘点报告已提交，差异处理正在后台执行");

        return ApiResponse.success(result);
    }

    @GetMapping("/async/task/{taskId}")
    public ApiResponse<InventoryAsyncService.TaskStatus> getTaskStatus(@PathVariable String taskId) {
        InventoryAsyncService.TaskStatus status = asyncService.getTaskStatus(taskId);
        if (status == null) {
            return ApiResponse.error(404, "任务不存在: " + taskId);
        }
        return ApiResponse.success(status);
    }

    @GetMapping("/async/tasks")
    public ApiResponse<Map<String, InventoryAsyncService.TaskStatus>> getAllTasks() {
        Map<String, InventoryAsyncService.TaskStatus> tasks = asyncService.getAllTaskStatus();
        return ApiResponse.success(tasks);
    }

    @PostMapping("/async/task/{taskId}/cancel")
    public ApiResponse<Void> cancelTask(@PathVariable String taskId) {
        asyncService.cancelTask(taskId);
        return ApiResponse.success();
    }

    @GetMapping("/check/{checkId}")
    public ApiResponse<InventoryCheck> getCheckById(@PathVariable String checkId) {
        InventoryCheck check = inventoryService.getCheckById(checkId);
        return ApiResponse.success(check);
    }

    @GetMapping("/check")
    public ApiResponse<List<InventoryCheck>> getAllChecks() {
        List<InventoryCheck> checks = inventoryService.getAllChecks();
        return ApiResponse.success(checks);
    }

    @GetMapping("/check/status/{status}")
    public ApiResponse<List<InventoryCheck>> getChecksByStatus(@PathVariable String status) {
        List<InventoryCheck> checks = inventoryService.getChecksByStatus(status);
        return ApiResponse.success(checks);
    }

    @GetMapping("/difference/check/{checkId}")
    public ApiResponse<List<InventoryDifference>> getDifferencesByCheck(@PathVariable String checkId) {
        List<InventoryDifference> diffs = inventoryService.getDifferencesByCheck(checkId);
        return ApiResponse.success(diffs);
    }

    @GetMapping("/difference/pending")
    public ApiResponse<List<InventoryDifference>> getPendingDifferences() {
        List<InventoryDifference> diffs = inventoryService.getPendingDifferences();
        return ApiResponse.success(diffs);
    }

    @PostMapping("/check/{checkId}/complete")
    public ApiResponse<Void> completeCheck(@PathVariable String checkId) {
        inventoryService.completeCheck(checkId);
        return ApiResponse.success();
    }
}
