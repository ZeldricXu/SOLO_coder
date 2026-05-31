package com.smartflow.approvalengine.controller;

import com.smartflow.common.base.Result;
import com.smartflow.common.dto.ApprovalRequest;
import com.smartflow.approvalengine.service.ApprovalEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/approval")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalEngineService approvalEngineService;

    @PostMapping("/start")
    public Result<Map<String, Object>> startApproval(@RequestBody ApprovalRequest request) {
        Map<String, Object> result = approvalEngineService.startApproval(request);
        return Result.success(result);
    }

    @PostMapping("/approve")
    public Result<Map<String, Object>> approve(
            @RequestParam Long instanceId,
            @RequestParam Long approverId,
            @RequestParam Integer action,
            @RequestParam(required = false) String comment) {
        Map<String, Object> result = approvalEngineService.approve(instanceId, approverId, action, comment);
        return Result.success(result);
    }

    @GetMapping("/{instanceId}")
    public Result<Map<String, Object>> getDetail(@PathVariable Long instanceId) {
        Map<String, Object> detail = approvalEngineService.getApprovalDetail(instanceId);
        return Result.success(detail);
    }
}
