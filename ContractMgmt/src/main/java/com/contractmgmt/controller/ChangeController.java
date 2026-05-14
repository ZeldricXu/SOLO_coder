package com.contractmgmt.controller;

import com.contractmgmt.dto.ApiResponse;
import com.contractmgmt.dto.ChangeRequest;
import com.contractmgmt.entity.ChangeRecord;
import com.contractmgmt.service.ChangeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/changes")
public class ChangeController {

    private final ChangeService changeService;

    public ChangeController(ChangeService changeService) {
        this.changeService = changeService;
    }

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createChange(
            @Valid @RequestBody ChangeRequest request) {
        Map<String, Object> result = changeService.createChangeRequest(request);
        return ApiResponse.success(result);
    }

    @PostMapping("/{changeId}/approve")
    public ApiResponse<Map<String, Object>> approveChange(
            @PathVariable String changeId,
            @RequestParam String approver,
            @RequestParam(required = false) String comment) {
        Map<String, Object> result = changeService.approveChange(
                changeId, approver, comment, true);
        return ApiResponse.success(result);
    }

    @PostMapping("/{changeId}/reject")
    public ApiResponse<Map<String, Object>> rejectChange(
            @PathVariable String changeId,
            @RequestParam String approver,
            @RequestParam(required = false) String comment) {
        Map<String, Object> result = changeService.approveChange(
                changeId, approver, comment, false);
        return ApiResponse.success(result);
    }

    @GetMapping("/{changeId}")
    public ApiResponse<ChangeRecord> getChangeRecord(@PathVariable String changeId) {
        ChangeRecord record = changeService.getChangeRecord(changeId);
        return ApiResponse.success(record);
    }

    @GetMapping("/contract/{contractId}")
    public ApiResponse<List<ChangeRecord>> getContractChanges(@PathVariable String contractId) {
        List<ChangeRecord> changes = changeService.getChangeHistory(contractId);
        return ApiResponse.success(changes);
    }

    @GetMapping("/pending")
    public ApiResponse<List<ChangeRecord>> getPendingChanges() {
        List<ChangeRecord> changes = changeService.getPendingChanges();
        return ApiResponse.success(changes);
    }
}
