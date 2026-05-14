package com.contractmgmt.controller;

import com.contractmgmt.dto.ApiResponse;
import com.contractmgmt.dto.ApprovalRequest;
import com.contractmgmt.entity.ApprovalRecord;
import com.contractmgmt.service.ApprovalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/contracts")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/approve")
    public ApiResponse<Map<String, Object>> approveContract(
            @Valid @RequestBody ApprovalRequest request) {
        Map<String, Object> result = approvalService.processApproval(request);
        return ApiResponse.success(result);
    }

    @GetMapping("/{contractId}/approvals")
    public ApiResponse<List<ApprovalRecord>> getApprovalHistory(@PathVariable String contractId) {
        List<ApprovalRecord> approvals = approvalService.getApprovalHistory(contractId);
        return ApiResponse.success(approvals);
    }

    @GetMapping("/approvals/by-approver")
    public ApiResponse<List<ApprovalRecord>> getApprovalsByApprover(
            @RequestParam String approver) {
        List<ApprovalRecord> approvals = approvalService.getApprovalsByApprover(approver);
        return ApiResponse.success(approvals);
    }
}
