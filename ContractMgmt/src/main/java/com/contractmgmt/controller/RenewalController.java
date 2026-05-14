package com.contractmgmt.controller;

import com.contractmgmt.dto.ApiResponse;
import com.contractmgmt.dto.RenewalRequest;
import com.contractmgmt.entity.RenewalRecord;
import com.contractmgmt.service.RenewalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/renewals")
public class RenewalController {

    private final RenewalService renewalService;

    public RenewalController(RenewalService renewalService) {
        this.renewalService = renewalService;
    }

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createRenewal(
            @Valid @RequestBody RenewalRequest request) {
        Map<String, Object> result = renewalService.createRenewalRequest(request);
        return ApiResponse.success(result);
    }

    @PostMapping("/{renewalId}/approve")
    public ApiResponse<Map<String, Object>> approveRenewal(
            @PathVariable String renewalId,
            @RequestParam String approver,
            @RequestParam(required = false) String comment) {
        Map<String, Object> result = renewalService.approveRenewal(
                renewalId, approver, comment, true);
        return ApiResponse.success(result);
    }

    @PostMapping("/{renewalId}/reject")
    public ApiResponse<Map<String, Object>> rejectRenewal(
            @PathVariable String renewalId,
            @RequestParam String approver,
            @RequestParam(required = false) String comment) {
        Map<String, Object> result = renewalService.approveRenewal(
                renewalId, approver, comment, false);
        return ApiResponse.success(result);
    }

    @GetMapping("/{renewalId}")
    public ApiResponse<RenewalRecord> getRenewalRecord(@PathVariable String renewalId) {
        RenewalRecord record = renewalService.getRenewalRecord(renewalId);
        return ApiResponse.success(record);
    }

    @GetMapping("/contract/{contractId}")
    public ApiResponse<List<RenewalRecord>> getContractRenewals(@PathVariable String contractId) {
        List<RenewalRecord> renewals = renewalService.getRenewalHistory(contractId);
        return ApiResponse.success(renewals);
    }

    @GetMapping("/pending")
    public ApiResponse<List<RenewalRecord>> getPendingRenewals() {
        List<RenewalRecord> renewals = renewalService.getPendingRenewals();
        return ApiResponse.success(renewals);
    }
}
