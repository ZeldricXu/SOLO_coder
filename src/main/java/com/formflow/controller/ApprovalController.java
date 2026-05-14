package com.formflow.controller;

import com.formflow.common.ApiResponse;
import com.formflow.dto.ApprovalProcessRequest;
import com.formflow.dto.ApprovalProcessResponse;
import com.formflow.entity.ApprovalRecord;
import com.formflow.entity.ApprovalTask;
import com.formflow.service.ApprovalRecordService;
import com.formflow.service.ApprovalTaskService;
import com.formflow.service.ProcessEngineService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalController.class);

    @Autowired
    private ProcessEngineService processEngineService;

    @Autowired
    private ApprovalTaskService approvalTaskService;

    @Autowired
    private ApprovalRecordService approvalRecordService;

    @PostMapping("/process")
    public ApiResponse<ApprovalProcessResponse> processApproval(
            @Valid @RequestBody ApprovalProcessRequest request) {
        logger.info("接收审批处理请求: taskId={}, result={}",
                request.getTaskId(), request.getApprovalResult());

        ApprovalProcessResponse response = processEngineService.processApproval(request);
        return ApiResponse.success("审批处理成功", response);
    }

    @GetMapping("/tasks/my/{approverId}")
    public ApiResponse<List<ApprovalTask>> getMyTasks(@PathVariable String approverId) {
        logger.info("查询用户审批任务: approverId={}", approverId);
        List<ApprovalTask> tasks = approvalTaskService.getTasksByApproverId(approverId);
        return ApiResponse.success(tasks);
    }

    @GetMapping("/tasks/my/{approverId}/pending")
    public ApiResponse<List<ApprovalTask>> getMyPendingTasks(@PathVariable String approverId) {
        logger.info("查询用户待处理审批任务: approverId={}", approverId);
        List<ApprovalTask> tasks = approvalTaskService.getPendingTasksByApproverId(approverId);
        return ApiResponse.success(tasks);
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<ApprovalTask> getTaskDetail(@PathVariable String taskId) {
        logger.info("查询审批任务详情: taskId={}", taskId);
        ApprovalTask task = approvalTaskService.getTaskByTaskId(taskId);
        return ApiResponse.success(task);
    }

    @GetMapping("/history/instance/{instanceId}")
    public ApiResponse<List<ApprovalRecord>> getApprovalHistory(@PathVariable String instanceId) {
        logger.info("查询流程审批历史: instanceId={}", instanceId);
        List<ApprovalRecord> records = approvalRecordService.getRecordsByInstanceId(instanceId);
        return ApiResponse.success(records);
    }

    @GetMapping("/history/form/{formId}")
    public ApiResponse<List<ApprovalRecord>> getFormApprovalHistory(@PathVariable String formId) {
        logger.info("查询表单审批历史: formId={}", formId);
        List<ApprovalRecord> records = approvalRecordService.getRecordsByFormId(formId);
        return ApiResponse.success(records);
    }

    @PostMapping("/tasks/{taskId}/transfer")
    public ApiResponse<Void> transferTask(
            @PathVariable String taskId,
            @RequestParam String newApproverId,
            @RequestParam(required = false) String newApproverName,
            @RequestParam(required = false) String comment) {
        logger.info("转交审批任务: taskId={}, newApproverId={}", taskId, newApproverId);
        approvalTaskService.transferTask(taskId, newApproverId, newApproverName, comment);
        return ApiResponse.success("任务转交成功", null);
    }

    @PostMapping("/tasks/{taskId}/delegate")
    public ApiResponse<Void> delegateTask(
            @PathVariable String taskId,
            @RequestParam String delegateApproverId,
            @RequestParam(required = false) String delegateApproverName) {
        logger.info("委托审批任务: taskId={}, delegateApproverId={}", taskId, delegateApproverId);
        approvalTaskService.delegateTask(taskId, delegateApproverId, delegateApproverName);
        return ApiResponse.success("任务委托成功", null);
    }

    @GetMapping("/tasks/stats/{approverId}")
    public ApiResponse<Map<String, Long>> getTaskStats(@PathVariable String approverId) {
        logger.info("查询审批任务统计: approverId={}", approverId);
        Map<String, Long> stats = new HashMap<>();
        stats.put("pending", approvalTaskService.countPendingTasksByApproverId(approverId));
        stats.put("total", approvalTaskService.countTotalTasksByApproverId(approverId));
        return ApiResponse.success(stats);
    }
}
