package com.orchestration.approval.controller;

import com.orchestration.common.api.ApiConstants;
import com.orchestration.common.base.Result;
import com.orchestration.approval.service.ApprovalService;
import com.orchestration.persistence.entity.ApprovalFlow;
import com.orchestration.persistence.entity.ApprovalInstance;
import com.orchestration.persistence.entity.ApprovalTask;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.API_V1_PREFIX + "/approval")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    @PostMapping("/flows")
    public Result<Long> createFlow(@RequestBody ApprovalFlow flow) {
        return Result.success(approvalService.createFlow(flow));
    }

    @PutMapping("/flows/{id}")
    public Result<Boolean> updateFlow(@PathVariable Long id, @RequestBody ApprovalFlow flow) {
        flow.setId(id);
        return Result.success(approvalService.updateFlow(flow));
    }

    @GetMapping("/flows/{id}")
    public Result<ApprovalFlow> getFlow(@PathVariable Long id) {
        return Result.success(approvalService.getFlow(id));
    }

    @GetMapping("/flows")
    public Result<List<ApprovalFlow>> listFlows(
            @RequestParam(required = false) String flowType) {
        return Result.success(approvalService.listFlows(flowType));
    }

    @DeleteMapping("/flows/{id}")
    public Result<Boolean> deleteFlow(@PathVariable Long id) {
        return Result.success(approvalService.deleteFlow(id));
    }

    @PostMapping("/instances/start")
    public Result<Long> startInstance(
            @RequestParam String flowCode,
            @RequestParam String businessKey,
            @RequestBody(required = false) Map<String, Object> businessData,
            @RequestParam Long initiatorId) {
        return Result.success(approvalService.startInstance(flowCode, businessKey, businessData, initiatorId));
    }

    @GetMapping("/instances/{id}")
    public Result<ApprovalInstance> getInstance(@PathVariable Long id) {
        return Result.success(approvalService.getInstance(id));
    }

    @GetMapping("/instances")
    public Result<List<ApprovalInstance>> listInstances(
            @RequestParam(required = false) Long initiatorId,
            @RequestParam(required = false) String status) {
        return Result.success(approvalService.listInstances(initiatorId, status));
    }

    @PostMapping("/tasks/{taskId}/approve")
    public Result<Boolean> approve(
            @PathVariable Long taskId,
            @RequestParam Long userId,
            @RequestBody(required = false) String comment) {
        return Result.success(approvalService.approve(taskId, userId, comment));
    }

    @PostMapping("/tasks/{taskId}/reject")
    public Result<Boolean> reject(
            @PathVariable Long taskId,
            @RequestParam Long userId,
            @RequestBody(required = false) String comment) {
        return Result.success(approvalService.reject(taskId, userId, comment));
    }

    @PostMapping("/tasks/{taskId}/delegate")
    public Result<Boolean> delegate(
            @PathVariable Long taskId,
            @RequestParam Long fromUserId,
            @RequestParam Long toUserId) {
        return Result.success(approvalService.delegate(taskId, fromUserId, toUserId));
    }

    @PostMapping("/tasks/{taskId}/transfer")
    public Result<Boolean> transfer(
            @PathVariable Long taskId,
            @RequestParam Long fromUserId,
            @RequestParam Long toUserId) {
        return Result.success(approvalService.transfer(taskId, fromUserId, toUserId));
    }

    @PostMapping("/instances/{instanceId}/cancel")
    public Result<Boolean> cancelInstance(
            @PathVariable Long instanceId,
            @RequestParam Long userId) {
        return Result.success(approvalService.cancelInstance(instanceId, userId));
    }

    @GetMapping("/tasks/user/{userId}")
    public Result<List<ApprovalTask>> listUserTasks(
            @PathVariable Long userId,
            @RequestParam(required = false) String status) {
        return Result.success(approvalService.listUserTasks(userId, status));
    }

    @GetMapping("/tasks/{id}")
    public Result<ApprovalTask> getTask(@PathVariable Long id) {
        return Result.success(approvalService.getTask(id));
    }

    @GetMapping("/instances/{instanceId}/diagram")
    public Result<Map<String, Object>> getFlowDiagram(@PathVariable Long instanceId) {
        return Result.success(approvalService.getFlowDiagram(instanceId));
    }

    @PostMapping("/instances/{instanceId}/approvers")
    public Result<Boolean> setDynamicApprovers(
            @PathVariable Long instanceId,
            @RequestParam String nodeId,
            @RequestBody List<Long> approverIds) {
        return Result.success(approvalService.setDynamicApprovers(instanceId, nodeId, approverIds));
    }
}
