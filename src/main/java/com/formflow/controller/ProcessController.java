package com.formflow.controller;

import com.formflow.common.ApiResponse;
import com.formflow.dto.ProcessStatusResponse;
import com.formflow.entity.FormData;
import com.formflow.entity.ProcessDefinition;
import com.formflow.entity.ProcessInstance;
import com.formflow.entity.ApprovalRecord;
import com.formflow.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/processes")
public class ProcessController {

    private static final Logger logger = LoggerFactory.getLogger(ProcessController.class);

    @Autowired
    private ProcessEngineService processEngineService;

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private FormDataService formDataService;

    @Autowired
    private ApprovalRecordService approvalRecordService;

    @Autowired
    private StatisticsService statisticsService;

    @GetMapping("/status")
    public ApiResponse<ProcessStatusResponse> getProcessStatus(
            @RequestParam(required = false) String instanceId,
            @RequestParam(required = false) String formId) {
        logger.info("查询流程状态: instanceId={}, formId={}", instanceId, formId);

        ProcessInstance instance;
        if (instanceId != null && !instanceId.isEmpty()) {
            instance = processEngineService.getInstanceByInstanceId(instanceId);
        } else if (formId != null && !formId.isEmpty()) {
            instance = processEngineService.getInstanceByFormId(formId);
        } else {
            return ApiResponse.badRequest("请提供instanceId或formId");
        }

        FormData formData = formDataService.getFormByFormId(instance.getFormId());
        List<ApprovalRecord> history = approvalRecordService.getRecordsByInstanceId(instance.getInstanceId());

        ProcessStatusResponse response = ProcessStatusResponse.builder()
                .instance(instance)
                .history(history)
                .formData(formData.getFormData())
                .formStatus(formData.getStatus().name())
                .build();

        return ApiResponse.success(response);
    }

    @GetMapping("/definitions")
    public ApiResponse<List<ProcessDefinition>> getProcessDefinitions() {
        logger.info("查询流程定义列表");
        List<ProcessDefinition> definitions = processDefinitionService.getEnabledProcessDefinitions();
        return ApiResponse.success(definitions);
    }

    @GetMapping("/definitions/{processId}")
    public ApiResponse<ProcessDefinition> getProcessDefinition(@PathVariable String processId) {
        logger.info("查询流程定义: processId={}", processId);
        ProcessDefinition definition = processDefinitionService.getProcessDefinition(processId);
        return ApiResponse.success(definition);
    }

    @PostMapping("/definitions")
    public ApiResponse<ProcessDefinition> createProcessDefinition(
            @RequestBody ProcessDefinition definition,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Name", required = false) String userName) {
        logger.info("创建流程定义: processName={}", definition.getProcessName());
        ProcessDefinition created = processDefinitionService.createProcessDefinition(definition, userId, userName);
        return ApiResponse.success("流程定义创建成功", created);
    }

    @PutMapping("/definitions/{processId}")
    public ApiResponse<ProcessDefinition> updateProcessDefinition(
            @PathVariable String processId,
            @RequestBody ProcessDefinition definition) {
        logger.info("更新流程定义: processId={}", processId);
        ProcessDefinition updated = processDefinitionService.updateProcessDefinition(processId, definition);
        return ApiResponse.success("流程定义更新成功", updated);
    }

    @DeleteMapping("/definitions/{processId}")
    public ApiResponse<Void> deleteProcessDefinition(@PathVariable String processId) {
        logger.info("删除流程定义: processId={}", processId);
        processDefinitionService.deleteProcessDefinition(processId);
        return ApiResponse.success("流程定义删除成功", null);
    }

    @GetMapping("/my/{submitterId}")
    public ApiResponse<List<ProcessInstance>> getMyProcesses(@PathVariable String submitterId) {
        logger.info("查询用户发起的流程: submitterId={}", submitterId);
        List<ProcessInstance> instances = processEngineService.getInstancesBySubmitterId(submitterId);
        return ApiResponse.success(instances);
    }

    @GetMapping("/instances/{instanceId}")
    public ApiResponse<ProcessInstance> getProcessInstance(@PathVariable String instanceId) {
        logger.info("查询流程实例: instanceId={}", instanceId);
        ProcessInstance instance = processEngineService.getInstanceByInstanceId(instanceId);
        return ApiResponse.success(instance);
    }

    @GetMapping("/instances")
    public ApiResponse<List<ProcessInstance>> getProcessInstances(
            @RequestParam(required = false) String processId,
            @RequestParam(required = false) String submitterId) {
        logger.info("查询流程实例列表: processId={}, submitterId={}", processId, submitterId);

        List<ProcessInstance> instances;
        if (processId != null && !processId.isEmpty()) {
            instances = processEngineService.getInstancesByProcessId(processId);
        } else if (submitterId != null && !submitterId.isEmpty()) {
            instances = processEngineService.getInstancesBySubmitterId(submitterId);
        } else {
            instances = processEngineService.getInstancesByProcessId(null);
        }

        return ApiResponse.success(instances);
    }
}
