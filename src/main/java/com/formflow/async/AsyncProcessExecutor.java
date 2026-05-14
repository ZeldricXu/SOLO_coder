package com.formflow.async;

import com.formflow.entity.*;
import com.formflow.enums.*;
import com.formflow.event.ApprovalEvents;
import com.formflow.repository.ProcessInstanceRepository;
import com.formflow.service.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class AsyncProcessExecutor {

    private static final Logger logger = LoggerFactory.getLogger(AsyncProcessExecutor.class);

    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(10);

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private ApprovalTaskService approvalTaskService;

    @Autowired
    private FormDataService formDataService;

    @Autowired
    private FormTemplateService formTemplateService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Value("${formflow.process.task-due-days:1}")
    private int defaultTaskDueDays;

    @Async("processExecutor")
    public CompletableFuture<Void> processApprovalResultAsync(ProcessInstance instance,
                                                              String approvalResult,
                                                              Map<String, Object> variables) {
        try {
            logger.info("异步处理审批结果: instanceId={}, result={}",
                    instance.getInstanceId(), approvalResult);

            if (ApprovalResult.REJECTED.name().equalsIgnoreCase(approvalResult)) {
                handleRejection(instance);
                return CompletableFuture.completedFuture(null);
            }

            if (ApprovalResult.APPROVED.name().equalsIgnoreCase(approvalResult)) {
                ProcessDefinition processDefinition = processDefinitionService
                        .getProcessDefinition(instance.getProcessId());

                ProcessNode currentNode = processDefinitionService.getNodeById(
                        processDefinition, instance.getCurrentNodeId());

                if (!isNodeComplete(instance, currentNode)) {
                    logger.info("节点审批未完成，等待其他审批人: nodeId={}", currentNode.getNodeId());
                    return CompletableFuture.completedFuture(null);
                }

                moveToNextNodeAsync(instance, processDefinition, "approved", variables);
            }

        } catch (Exception e) {
            logger.error("异步处理审批结果失败: instanceId={}, error={}",
                    instance.getInstanceId(), e.getMessage(), e);
        }

        return CompletableFuture.completedFuture(null);
    }

    private boolean isNodeComplete(ProcessInstance instance, ProcessNode node) {
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

    @Async("processExecutor")
    public void moveToNextNodeAsync(ProcessInstance instance,
                                     ProcessDefinition processDefinition,
                                     String condition,
                                     Map<String, Object> variables) {
        try {
            moveToNextNodeInternal(instance, processDefinition, condition, variables);
        } catch (Exception e) {
            logger.error("异步流转失败: instanceId={}, error={}",
                    instance.getInstanceId(), e.getMessage(), e);
        }
    }

    private void moveToNextNodeInternal(ProcessInstance instance,
                                        ProcessDefinition processDefinition,
                                        String condition,
                                        Map<String, Object> variables) {
        ProcessNode currentNode = processDefinitionService.getNodeById(
                processDefinition, instance.getCurrentNodeId());

        if (processDefinitionService.isEndNode(processDefinition, instance.getCurrentNodeId())) {
            completeProcess(instance, currentNode, "approved");
            return;
        }

        ProcessTransition nextTransition = processDefinitionService.findNextTransition(
                processDefinition, instance.getCurrentNodeId(), condition, variables);

        if (nextTransition == null) {
            logger.warn("未找到合适的流转规则，流程结束: instanceId={}, currentNode={}",
                    instance.getInstanceId(), instance.getCurrentNodeId());
            completeProcess(instance, currentNode, "approved");
            return;
        }

        String nextNodeId = nextTransition.getToNode();
        ProcessNode nextNode = processDefinitionService.getNodeById(processDefinition, nextNodeId);

        if (nextNode == null) {
            throw new RuntimeException("目标节点不存在: " + nextNodeId);
        }

        String previousNodeId = instance.getCurrentNodeId();
        String previousNodeName = currentNode != null ? currentNode.getNodeName() : previousNodeId;

        instance.setPreviousNodeId(previousNodeId);
        instance.setCurrentNodeId(nextNodeId);
        processInstanceRepository.save(instance);

        ApprovalEvents.ProcessTransitionEvent transitionEvent = ApprovalEvents.ProcessTransitionEvent.builder()
                .instanceId(instance.getInstanceId())
                .formId(instance.getFormId())
                .fromNodeId(previousNodeId)
                .fromNodeName(previousNodeName)
                .toNodeId(nextNodeId)
                .toNodeName(nextNode.getNodeName())
                .transitionReason(nextTransition.getCondition())
                .variables(variables)
                .transitionTime(LocalDateTime.now())
                .build();
        eventPublisher.publishEvent(transitionEvent);

        if (nextNode.getNodeType() == NodeType.END) {
            completeProcess(instance, nextNode, "approved");
            return;
        }

        if (nextNode.getNodeType() == NodeType.APPROVAL) {
            FormData formData = formDataService.getFormByInstanceId(instance.getInstanceId());
            FormTemplate template = formTemplateService.getTemplateByTemplateId(formData.getTemplateId());
            distributeApprovalTasksAsync(instance, nextNode, variables, formData, template);
            return;
        }

        if (nextNode.getNodeType() == NodeType.CONDITION) {
            moveToNextNodeInternal(instance, processDefinition, condition, variables);
            return;
        }

        moveToNextNodeInternal(instance, processDefinition, "always", variables);
    }

    @Async("processExecutor")
    public void distributeApprovalTasksAsync(ProcessInstance instance,
                                              ProcessNode node,
                                              Map<String, Object> variables,
                                              FormData formData,
                                              FormTemplate template) {
        try {
            List<String> approvers = determineApprovers(node, instance, variables);

            if (approvers == null || approvers.isEmpty()) {
                throw new RuntimeException("节点未配置审批人: " + node.getNodeName());
            }

            List<CompletableFuture<ApprovalTask>> futures = approvers.stream()
                    .filter(approverId -> shouldCreateTaskForApprover(node, instance, approverId))
                    .map(approverId -> CompletableFuture.supplyAsync(() ->
                                    createApprovalTaskInternal(instance, node, approverId, formData, template),
                            parallelExecutor))
                    .toList();

            List<ApprovalTask> tasks = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .toList();

            if (tasks.isEmpty()) {
                throw new RuntimeException("未找到有效的审批人: " + node.getNodeName());
            }

            String approverIds = String.join(",", approvers);
            formDataService.updateCurrentApprovers(formData.getFormId(), approverIds);

            logger.info("并行分发审批任务完成: nodeId={}, taskCount={}", node.getNodeId(), tasks.size());

        } catch (Exception e) {
            logger.error("并行分发审批任务失败: instanceId={}, nodeId={}, error={}",
                    instance.getInstanceId(), node.getNodeId(), e.getMessage(), e);
        }
    }

    private ApprovalTask createApprovalTaskInternal(ProcessInstance instance,
                                                    ProcessNode node,
                                                    String approverId,
                                                    FormData formData,
                                                    FormTemplate template) {
        try {
            int taskDueDays = node.getTaskDueDays(defaultTaskDueDays);

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

            ApprovalEvents.ApprovalTaskCreatedEvent event = ApprovalEvents.fromApprovalTask(task);
            eventPublisher.publishEvent(event);

            notificationService.sendApprovalNotification(task, instance);

            return task;

        } catch (Exception e) {
            logger.error("创建审批任务失败: approverId={}, error={}", approverId, e.getMessage());
            return null;
        }
    }

    private boolean shouldCreateTaskForApprover(ProcessNode node, ProcessInstance instance, String approverId) {
        if (!Boolean.TRUE.equals(node.getCanApproveSelf()) &&
                approverId.equals(instance.getSubmitterId())) {
            logger.info("跳过提交人本人审批: approverId={}", approverId);
            return false;
        }
        return true;
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

    private void handleRejection(ProcessInstance instance) {
        instance.setInstanceStatus(ProcessInstanceStatus.REJECTED);
        instance.setEndTime(LocalDateTime.now());
        processInstanceRepository.save(instance);

        formDataService.updateFormStatus(instance.getFormId(), FormStatus.REJECTED);
        approvalTaskService.cancelPendingTasks(instance.getInstanceId());

        ApprovalEvents.ProcessCompletedEvent event = ApprovalEvents.ProcessCompletedEvent.builder()
                .instanceId(instance.getInstanceId())
                .formId(instance.getFormId())
                .approved(false)
                .finalApprovalResult(ApprovalResult.REJECTED.name())
                .endNodeId(instance.getCurrentNodeId())
                .endNodeName(getCurrentNodeName(instance))
                .variables(getInstanceVariables(instance))
                .completedTime(LocalDateTime.now())
                .build();
        eventPublisher.publishEvent(event);

        notificationService.sendProcessCompleteNotification(instance, false);

        logger.info("流程被拒绝: instanceId={}", instance.getInstanceId());
    }

    private void completeProcess(ProcessInstance instance, ProcessNode node, String result) {
        instance.setInstanceStatus(ProcessInstanceStatus.COMPLETED);
        instance.setEndTime(LocalDateTime.now());
        processInstanceRepository.save(instance);

        formDataService.updateFormStatus(instance.getFormId(), FormStatus.APPROVED);

        ApprovalEvents.ProcessCompletedEvent event = ApprovalEvents.ProcessCompletedEvent.builder()
                .instanceId(instance.getInstanceId())
                .formId(instance.getFormId())
                .approved(true)
                .finalApprovalResult(result)
                .endNodeId(node != null ? node.getNodeId() : instance.getCurrentNodeId())
                .endNodeName(node != null ? node.getNodeName() : getCurrentNodeName(instance))
                .variables(getInstanceVariables(instance))
                .completedTime(LocalDateTime.now())
                .build();
        eventPublisher.publishEvent(event);

        notificationService.sendProcessCompleteNotification(instance, true);

        logger.info("流程完成: instanceId={}", instance.getInstanceId());
    }

    private String getCurrentNodeName(ProcessInstance instance) {
        try {
            ProcessDefinition definition = processDefinitionService.getProcessDefinition(instance.getProcessId());
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
}
