package com.designsystem.controller.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.designsystem.common.PageQuery;
import com.designsystem.common.Result;
import com.designsystem.entity.ApprovalRequest;
import com.designsystem.service.ApprovalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalApiController {

    private final ApprovalService approvalService;

    public ApprovalApiController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping
    public Result<IPage<ApprovalRequest>> list(PageQuery query,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String requestType) {
        return Result.success(approvalService.getApprovalPage(query, status, requestType));
    }

    @GetMapping("/pending")
    public Result<List<ApprovalRequest>> getPending() {
        return Result.success(approvalService.getPendingApprovals());
    }

    @GetMapping("/{id}")
    public Result<ApprovalRequest> getById(@PathVariable Long id) {
        return Result.success(approvalService.getApprovalById(id));
    }

    @PostMapping("/component-publish")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public Result<ApprovalRequest> createComponentPublishRequest(
            @RequestParam Long componentId,
            @RequestParam String version,
            @RequestBody(required = false) String changeContent) {
        return Result.success(approvalService.createComponentPublishRequest(componentId, version, changeContent));
    }

    @PostMapping("/token-change")
    @PreAuthorize("hasAnyRole('ADMIN', 'DESIGNER')")
    public Result<ApprovalRequest> createTokenChangeRequest(
            @RequestParam Long tokenId,
            @RequestParam String changeType,
            @RequestBody(required = false) String changeContent) {
        return Result.success(approvalService.createTokenChangeRequest(tokenId, changeType, changeContent));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'DESIGN_LEAD', 'COMPONENT_APPROVER')")
    public Result<ApprovalRequest> approve(@PathVariable Long id,
                                           @RequestBody(required = false) String comment) {
        return Result.success(approvalService.approveRequest(id, comment));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'DESIGN_LEAD', 'COMPONENT_APPROVER')")
    public Result<ApprovalRequest> reject(@PathVariable Long id,
                                          @RequestBody String reason) {
        return Result.success(approvalService.rejectRequest(id, reason));
    }

    @PostMapping("/rollback")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> rollbackVersion(@RequestParam Long componentId,
                                        @RequestParam String targetVersion) {
        approvalService.rollbackVersion(componentId, targetVersion);
        return Result.success();
    }
}
