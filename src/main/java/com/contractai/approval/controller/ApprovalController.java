package com.contractai.approval.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.contractai.approval.dto.ApprovalDTO;
import com.contractai.approval.entity.ApprovalProcess;
import com.contractai.approval.entity.ApprovalRule;
import com.contractai.approval.entity.ApprovalTask;
import com.contractai.approval.service.ApprovalService;
import com.contractai.common.result.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    @PostMapping("/rules")
    public ApiResponse<ApprovalRule> createRule(@RequestBody ApprovalDTO.RuleCreateDTO dto) {
        return ApiResponse.success(approvalService.createRule(dto));
    }

    @PutMapping("/rules/{id}")
    public ApiResponse<ApprovalRule> updateRule(@PathVariable Long id, @RequestBody ApprovalDTO.RuleUpdateDTO dto) {
        return ApiResponse.success(approvalService.updateRule(id, dto));
    }

    @GetMapping("/rules")
    public ApiResponse<Page<ApprovalRule>> listRules(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String ruleType,
            @RequestParam(required = false) String businessType,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.success(approvalService.listRules(page, size, ruleType, businessType, enabled));
    }

    @GetMapping("/rules/{id}")
    public ApiResponse<ApprovalRule> getRule(@PathVariable Long id) {
        return ApiResponse.success(approvalService.getRule(id));
    }

    @DeleteMapping("/rules/{id}")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        approvalService.deleteRule(id);
        return ApiResponse.success();
    }

    @PostMapping("/processes")
    public ApiResponse<ApprovalDTO.ProcessStartResultDTO> startProcess(@RequestBody ApprovalDTO.ProcessStartDTO dto) {
        return ApiResponse.success(approvalService.startProcess(dto));
    }

    @GetMapping("/processes/{id}")
    public ApiResponse<ApprovalProcess> getProcess(@PathVariable Long id) {
        return ApiResponse.success(approvalService.getProcess(id));
    }

    @GetMapping("/processes")
    public ApiResponse<Page<ApprovalProcess>> listProcesses(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String businessType,
            @RequestParam(required = false) Long starterId) {
        return ApiResponse.success(approvalService.listProcesses(page, size, status, businessType, starterId));
    }

    @PostMapping("/processes/cancel")
    public ApiResponse<ApprovalProcess> cancelProcess(@RequestBody ApprovalDTO.CancelProcessDTO dto) {
        return ApiResponse.success(approvalService.cancelProcess(dto));
    }

    @PostMapping("/tasks/approve")
    public ApiResponse<ApprovalTask> approve(@RequestBody ApprovalDTO.ApproveDTO dto) {
        return ApiResponse.success(approvalService.approve(dto));
    }

    @GetMapping("/tasks")
    public ApiResponse<Page<ApprovalTask>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long approverId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long processId) {
        return ApiResponse.success(approvalService.listTasks(page, size, approverId, status, processId));
    }

    @PostMapping("/add-sign")
    public ApiResponse<List<ApprovalTask>> addSign(@RequestBody ApprovalDTO.AddSignDTO dto) {
        return ApiResponse.success(approvalService.addSign(dto));
    }

    @PostMapping("/evaluate-condition")
    public ApiResponse<Boolean> evaluateCondition(@RequestBody ApprovalDTO.ConditionEvaluationDTO dto) {
        return ApiResponse.success(approvalService.evaluateCondition(dto));
    }

    @PostMapping("/resolve-approvers")
    public ApiResponse<List<Long>> resolveDynamicApprovers(@RequestBody ApprovalDTO.DynamicApproverDTO dto) {
        return ApiResponse.success(approvalService.resolveDynamicApprovers(dto));
    }
}
