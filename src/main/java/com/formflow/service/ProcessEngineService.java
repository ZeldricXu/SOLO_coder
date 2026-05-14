package com.formflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formflow.async.AsyncProcessExecutor;
import com.formflow.dto.ApprovalProcessRequest;
import com.formflow.dto.ApprovalProcessResponse;
import com.formflow.dto.FormSubmitRequest;
import com.formflow.dto.FormSubmitResponse;
import com.formflow.entity.*;
import com.formflow.enums.*;
import com.formflow.exception.BusinessException;
import com.formflow.repository.ProcessInstanceRepository;
import com.formflow.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class ProcessEngineService {

    private static final Logger logger = LoggerFactory.getLogger(ProcessEngineService.class);

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    @Autowired
    private FormTemplateService formTemplateService;

    @Autowired
    private FormDataService formDataService;

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private ApprovalTaskService approvalTaskService;

    @Autowired
    private ApprovalRecordService approvalRecordService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AsyncProcessExecutor asyncProcessExecutor;

    @Value("${formflow.process.task-due-days:1}")
    private int defaultTaskDueDays;

    @Transactional
    public FormSubmitResponse submitFormAndStartProcess(FormSubmitRequest request) {
        logger.info("开始处理表单提交并启动流程: templateId={}", request.getTemplateId());

        FormTemplate template = formTemplateService.getEnabledTemplate(request.getTemplateId());

        if (template.getProcessDefinitionId() == null || template.getProcessDefinitionId().isEmpty()) {
            throw new BusinessException("表单模板未关联流程定义");
        }

        ProcessDefinition processDefinition = processDefinitionService
                .getEnabledProcessDefinition(template.getProcessDefinitionId());

        formTemplateService.validateFormData(template, request.getFormData());

        FormData formData = formDataService.submitForm(
                request.getTemplateId(),
                request.getFormData(),
                request.getSubmitterId(),
                request.getSubmitterName(),
                request.getRemark()
        );

        ProcessInstance processInstance = startProcess(
                template.getProcessDefinitionId(),
                formData.getFormId(),
                request.getSubmitterId(),
                request.getSubmitterName(),
                request.getFormData()
        );

        formDataService.updateProcessInstanceId(formData.getFormId(), processInstance.getInstanceId());

        FormSubmitResponse response = FormSubmitResponse.builder()
                .formId(formData.getFormId())
                .instanceId(processInstance.getInstanceId())
                .status(processInstance.getInstanceStatus().name())
                .currentNodeName(getCurrentNodeName(processInstance))
                .build();

        logger.info("表单提交并启动流程完成: formId={}, instanceId={}",
                formData.getFormId(), processInstance.getInstanceId());

        return response;
    }

    @Transactional
    public ProcessInstance startProcess(String processId, String formId,
                                        String submitterId, String submitterName,
                                        Map<String, Object> formDataMap) {
        logger.info("启动流程: processId={}, formId={}", processId, formId);

        ProcessDefinition processDefinition = processDefinitionService.getEnabledProcessDefinition(processId);

        ProcessInstance instance = new ProcessInstance();
        instance.setInstanceId(IdGenerator.generateInstanceId());
        instance.setProcessId(processId);
        instance.setFormId(formId);
        instance.setSubmitterId(submitterId);
        instance.setSubmitterName(submitterName);
        instance.setInstanceStatus(ProcessInstanceStatus.RUNNING);
        instance.setCurrentNodeId(processDefinition.getStartNodeId());

        if (formDataMap != null && !formDataMap.isEmpty()) {
            try {
                instance.setVariables(objectMapper.writeValueAsString(formDataMap));
            } catch (JsonProcessingException e) {
                logger.warn("流程变量序列化失败: {}", e.getMessage());
            }
        }

        ProcessInstance savedInstance = processInstanceRepository.save(instance);

        moveToNextNode(savedInstance, processDefinition, "approved", formDataMap);

        logger.info("流程启动成功: instanceId={}", savedInstance.getInstanceId());
        return savedInstance;
    }

    @Transactional
    public ApprovalProcessResponse processApproval(ApprovalProcessRequest request) {
        logger.info("处理审批: taskId={}, result={}", request.getTaskId(), request.getApprovalResult());

        ApprovalTask task = approvalTaskService.getTaskByTaskId(request.getTaskId());

        if (task.getTaskStatus() != TaskStatus.PENDING) {
            throw new BusinessException("审批任务已处理，状态: " + task.getTaskStatus());
        }

        ProcessInstance instance = getInstanceByInstanceId(task.getInstanceId());

        if (instance.getInstanceStatus() != ProcessInstanceStatus.RUNNING) {
            throw new BusinessException("流程已结束，状态: " + instance.getInstanceStatus());
        }

        if (!task.getNodeId().equals(instance.getCurrentNodeId())) {
            throw new BusinessException("当前节点不匹配");
        }

        ProcessDefinition processDefinition = processDefinitionService
                .getProcessDefinition(instance.getProcessId());

        ApprovalResult approvalResult = parseApprovalResult(request.getApprovalResult());

        approvalTaskService.completeTask(
                task.getTaskId(),
                approvalResult.name(),
                request.getApprovalComment(),
                request.getApproverId(),
                request.getApproverName()
        );

        approvalRecordService.createApprovalRecord(task, approvalResult, request.getApprovalComment());

        String currentNodeName = task.getNodeName();

        Map<String, Object> variables = getInstanceVariables(instance);

        if (approvalResult == ApprovalResult.APPROVED) {
            ProcessNode currentNode = processDefinitionService.getNodeById(
                    processDefinition, instance.getCurrentNodeId());

            if (!isNodeComplete(instance, currentNode, processDefinition)) {
                logger.info("节点审批未完成，等待其他审批人: nodeId={}", currentNode.getNodeId());
                return buildApprovalResponse(instance, currentNodeName, false, task.getInstanceId());
            }

            asyncProcessExecutor.processApprovalResultAsync(instance, approvalResult.name(), variables);

        } else if (approvalResult == ApprovalResult.REJECTED) {
            asyncProcessExecutor.processApprovalResultAsync(instance, approvalResult.name(), variables);
        }

        ApprovalProcessResponse response = ApprovalProcessResponse.builder()
                .approvalId(approvalRecordService.getLastApprovalId(task.getInstanceId()))
                .instanceId(instance.getInstanceId())
                .instanceStatus(instance.getInstanceStatus().name())
                .currentNodeName(currentNodeName)
                .isProcessCompleted(false)
                .build();

        logger.info("审批处理完成，异步流转已启动: taskId={}", request.getTaskId());
        return response;
    }

    private boolean isNodeComplete(ProcessInstance instance, ProcessNode node, ProcessDefinition definition) {
        if (node == null) {
            return true;
        }

        String strategy = node.getApprovalStrategy();
        if (strategy == null || strategy.isEmpty()) {
            strategy = "AND";
        }

        List<ApprovalTask> nodeTasks = approvalTaskService.getTasksByInstanceId(instance.getInstanceId())
                .stream()
                .filter(t -> node.getNodeId().equals(t.getNodeId()))
                .toList();

        if (nodeTasks.isEmpty()) {
            return true;
        }

        List<ApprovalTask> completedTasks = nodeTasks.stream()
                .filter(t -> t.getTaskStatus() == TaskStatus.COMPLETED)
                .toList();

        List<ApprovalTask> pendingTasks = nodeTasks.stream()
                .filter(t -> t.getTaskStatus() == TaskStatus.PENDING)
                .toList();

        if ("OR".equalsIgnoreCase(strategy)) {
            boolean hasApproved = completedTasks.stream()
                    .anyMatch(t -> ApprovalResult.APPROVED.name().equalsIgnoreCase(t.getApprovalResult()));
            if (hasApproved) {
                pendingTasks.forEach(t -> approvalTaskService.cancelTask(t.getTaskId()));
                return true;
            }
            return false;
        } else {
            if (!pendingTasks.isEmpty()) {
                return false;
            }

            boolean hasRejected = completedTasks.stream()
                    .anyMatch(t -> ApprovalResult.REJECTED.name().equalsIgnoreCase(t.getApprovalResult()));

            return !hasRejected;
        }
    }

    private ApprovalProcessResponse buildApprovalResponse(ProcessInstance instance,
                                                           String nodeName,
                                                           boolean isCompleted,
                                                           String instanceId) {
        return ApprovalProcessResponse.builder()
                .approvalId(approvalRecordService.getLastApprovalId(instanceId))
                .instanceId(instance.getInstanceId())
                .instanceStatus(instance.getInstanceStatus().name())
                .currentNodeName(nodeName)
                .isProcessCompleted(isCompleted)
                .build();
    }

    private void moveToNextNode(ProcessInstance instance,
                                 ProcessDefinition processDefinition,
                                 String condition,
                                 Map<String, Object> variables) {
        ProcessNode currentNode = processDefinitionService.getNodeById(
                processDefinition, instance.getCurrentNodeId());

        if (processDefinitionService.isEndNode(processDefinition, instance.getCurrentNodeId())) {
            completeProcess(instance);
            return;
        }

        ProcessTransition nextTransition = processDefinitionService.findNextTransition(
                processDefinition, instance.getCurrentNodeId(), condition, variables);

        if (nextTransition == null) {
            logger.warn("未找到合适的流转规则，流程结束: instanceId={}, currentNode={}",
                    instance.getInstanceId(), instance.getCurrentNodeId());
            completeProcess(instance);
            return;
        }

        String nextNodeId = nextTransition.getToNode();
        ProcessNode nextNode = processDefinitionService.getNodeById(processDefinition, nextNodeId);

        if (nextNode == null) {
            throw new BusinessException("目标节点不存在: " + nextNodeId);
        }

        instance.setPreviousNodeId(instance.getCurrentNodeId());
        instance.setCurrentNodeId(nextNodeId);
        processInstanceRepository.save(instance);

        if (nextNode.getNodeType() == NodeType.END) {
            completeProcess(instance);
            return;
        }

        if (nextNode.getNodeType() == NodeType.APPROVAL) {
            distributeApprovalTasks(instance, nextNode, variables);
            return;
        }

        if (nextNode.getNodeType() == NodeType.CONDITION) {
            moveToNextNode(instance, processDefinition, condition, variables);
            return;
        }

        moveToNextNode(instance, processDefinition, "always", variables);
    }

    private void distributeApprovalTasks(ProcessInstance instance, ProcessNode node,
                                         Map<String, Object> variables) {
        List<String> approvers = determineApprovers(node, instance, variables);

        if (approvers == null || approvers.isEmpty()) {
            throw new BusinessException("节点未配置审批人: " + node.getNodeName());
        }

        FormData formData = formDataService.getFormByInstanceId(instance.getInstanceId());
        FormTemplate template = formTemplateService.getTemplateByTemplateId(formData.getTemplateId());

        int taskDueDays = node.getTaskDueDays(defaultTaskDueDays);

        List<ApprovalTask> tasks = new ArrayList<>();
        for (String approverId : approvers) {
            if (!Boolean.TRUE.equals(node.getCanApproveSelf()) &&
                    approverId.equals(instance.getSubmitterId())) {
                logger.info("跳过提交人本人审批: approverId={}", approverId);
                continue;
            }

            ApprovalTask task = approvalTaskService.createApprovalTask(
                    instance.getInstanceId(),
                    node.getNodeId(),
                    node.getNodeName(),
                    formData.getFormId(),
                    formData.getTemplateId(),
                    approverId,
                    null,
                    instance.getSubmitterId(),
                    instance.getSubmitterName(),
                    template.getTemplateName(),
                    LocalDateTime.now().plusDays(taskDueDays)
            );
            tasks.add(task);

            notificationService.sendApprovalNotification(task, instance);
        }

        if (tasks.isEmpty()) {
            throw new BusinessException("未找到有效的审批人: " + node.getNodeName());
        }

        String approverIds = String.join(",", approvers);
        formDataService.updateCurrentApprovers(formData.getFormId(), approverIds);

        logger.info("分发审批任务完成: nodeId={}, taskCount={}, dueDays={}",
                node.getNodeId(), tasks.size(), taskDueDays);
    }

    private List<String> determineApprovers(ProcessNode node, ProcessInstance instance,
                                            Map<String, Object> variables) {
        List<String> approvers = new ArrayList<>();

        String approverType = node.getApproverType();

        if ("role".equalsIgnoreCase(approverType)) {
            approvers.addAll(getApproversByRole(node.getApproverRole()));
        } else if ("user".equalsIgnoreCase(approverType)) {
            if (node.getApproverUserIds() != null && !node.getApproverUserIds().isEmpty()) {
                approvers.addAll(Arrays.asList(node.getApproverUserIds().split(",")));
            }
        } else if (node.getApproverUserIds() != null && !node.getApproverUserIds().isEmpty()) {
            approvers.addAll(Arrays.asList(node.getApproverUserIds().split(",")));
        } else if (node.getApproverRole() != null && !node.getApproverRole().isEmpty()) {
            approvers.addAll(getApproversByRole(node.getApproverRole()));
        }

        return approvers;
    }

    private List<String> getApproversByRole(String role) {
        List<String> approvers = new ArrayList<>();
        if (role == null || role.isEmpty()) {
            return approvers;
        }

        Map<String, List<String>> roleApprovers = new HashMap<>();
        roleApprovers.put("manager", Arrays.asList("user_manager_01", "user_manager_02"));
        roleApprovers.put("hr", Arrays.asList("user_hr_01", "user_hr_02"));
        roleApprovers.put("director", Arrays.asList("user_director_01"));
        roleApprovers.put("admin", Arrays.asList("user_admin_01"));

        List<String> roleUsers = roleApprovers.get(role.toLowerCase());
        if (roleUsers != null) {
            approvers.addAll(roleUsers);
        }

        return approvers;
    }

    private void completeProcess(ProcessInstance instance) {
        instance.setInstanceStatus(ProcessInstanceStatus.COMPLETED);
        instance.setEndTime(LocalDateTime.now());
        processInstanceRepository.save(instance);
        logger.info("流程完成: instanceId={}", instance.getInstanceId());
    }

    private String getCurrentNodeName(ProcessInstance instance) {
        try {
            ProcessDefinition definition = processDefinitionService
                    .getProcessDefinition(instance.getProcessId());
            ProcessNode node = processDefinitionService.getNodeById(definition, instance.getCurrentNodeId());
            return node != null ? node.getNodeName() : instance.getCurrentNodeId();
        } catch (Exception e) {
            return instance.getCurrentNodeId();
        }
    }

    private Map<String, Object> getInstanceVariables(ProcessInstance instance) {
        if (instance.getVariables() == null || instance.getVariables().isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(instance.getVariables(), Map.class);
        } catch (JsonProcessingException e) {
            logger.warn("解析流程变量失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private ApprovalResult parseApprovalResult(String result) {
        if (result == null) {
            throw new BusinessException("审批结果不能为空");
        }
        try {
            return ApprovalResult.valueOf(result.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("不支持的审批结果: " + result);
        }
    }

    public ProcessInstance getInstanceByInstanceId(String instanceId) {
        return processInstanceRepository.findByInstanceId(instanceId)
                .orElseThrow(() -> new BusinessException(404, "流程实例不存在: " + instanceId));
    }

    public ProcessInstance getInstanceByFormId(String formId) {
        return processInstanceRepository.findByFormId(formId)
                .orElseThrow(() -> new BusinessException(404, "流程实例不存在，表单ID: " + formId));
    }

    public List<ProcessInstance> getInstancesBySubmitterId(String submitterId) {
        return processInstanceRepository.findBySubmitterId(submitterId);
    }

    public List<ProcessInstance> getInstancesByProcessId(String processId) {
        return processInstanceRepository.findByProcessId(processId);
    }
}
