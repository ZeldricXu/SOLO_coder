package com.assetinventory.controller;

import com.assetinventory.dto.ApiResponse;
import com.assetinventory.dto.DifferenceAlertResponse;
import com.assetinventory.dto.ProcessDifferenceRequest;
import com.assetinventory.entity.InventoryDifference;
import com.assetinventory.service.DifferenceService;
import com.assetinventory.util.DifferenceAlertManager.AlertRecord;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/diffs")
public class DifferenceController {

    private final DifferenceService differenceService;

    @Autowired
    public DifferenceController(DifferenceService differenceService) {
        this.differenceService = differenceService;
    }

    @PostMapping("/process")
    public ResponseEntity<ApiResponse<Map<String, String>>> processDifference(
            @Valid @RequestBody ProcessDifferenceRequest request) {
        InventoryDifference diff = differenceService.processDifference(
                request.getDiffId(),
                request
        );

        Map<String, String> data = new HashMap<>();
        data.put("status", "processed");
        data.put("diff_status", diff.getDiffStatus());

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<InventoryDifference>>> getAllDifferences() {
        List<InventoryDifference> diffs = differenceService.getAllDifferences();
        return ResponseEntity.ok(ApiResponse.success(diffs));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<InventoryDifference>>> getDifferencesByStatus(@PathVariable String status) {
        List<InventoryDifference> diffs = differenceService.getDifferencesByStatus(status);
        return ResponseEntity.ok(ApiResponse.success(diffs));
    }

    @GetMapping("/{diffId}")
    public ResponseEntity<ApiResponse<InventoryDifference>> getDifferenceById(@PathVariable String diffId) {
        return differenceService.getDifferenceById(diffId)
                .map(diff -> ResponseEntity.ok(ApiResponse.success(diff)))
                .orElse(ResponseEntity.ok(ApiResponse.error(404, "差异记录不存在")));
    }

    @PostMapping("/{diffId}/alert")
    public ResponseEntity<ApiResponse<DifferenceAlertResponse>> triggerAlert(@PathVariable String diffId) {
        DifferenceAlertResponse response = differenceService.checkAndSendAlert(diffId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{diffId}/alert-info")
    public ResponseEntity<ApiResponse<DifferenceAlertResponse>> getAlertInfo(@PathVariable String diffId) {
        DifferenceAlertResponse response = differenceService.getAlertInfo(diffId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/alerts")
    public ResponseEntity<ApiResponse<List<AlertRecord>>> getSentAlerts() {
        List<AlertRecord> alerts = differenceService.getSentAlerts();
        return ResponseEntity.ok(ApiResponse.success(alerts));
    }

    @GetMapping("/alerts/severity/{severity}")
    public ResponseEntity<ApiResponse<List<AlertRecord>>> getSentAlertsBySeverity(@PathVariable String severity) {
        List<AlertRecord> alerts = differenceService.getSentAlertsBySeverity(severity);
        return ResponseEntity.ok(ApiResponse.success(alerts));
    }

    @GetMapping("/alerts/count")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAlertCounts() {
        Map<String, Object> data = new HashMap<>();
        data.put("total_sent", differenceService.getSentAlertCount());
        data.put("enabled", differenceService.isAlertEnabled());

        List<String> severities = differenceService.getAvailableSeverities();
        Map<String, Integer> severityCounts = new HashMap<>();
        for (String severity : severities) {
            severityCounts.put(severity, differenceService.getSentAlertCountBySeverity(severity));
        }
        data.put("severity_counts", severityCounts);

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @DeleteMapping("/{diffId}/alert")
    public ResponseEntity<ApiResponse<Map<String, String>>> clearAlert(@PathVariable String diffId) {
        differenceService.clearAlert(diffId);
        Map<String, String> data = new HashMap<>();
        data.put("status", "cleared");
        data.put("diff_id", diffId);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @DeleteMapping("/alerts")
    public ResponseEntity<ApiResponse<Map<String, String>>> clearAllAlerts() {
        differenceService.clearAllAlerts();
        Map<String, String> data = new HashMap<>();
        data.put("status", "cleared");
        data.put("message", "所有提醒已清除");
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/alerts/reset")
    public ResponseEntity<ApiResponse<Map<String, String>>> resetAlertManager() {
        differenceService.resetAlertManager();
        Map<String, String> data = new HashMap<>();
        data.put("status", "reset");
        data.put("message", "提醒管理器已重置");
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
