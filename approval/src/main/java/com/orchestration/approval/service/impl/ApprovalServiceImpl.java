package com.orchestration.approval.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.orchestration.common.exception.BusinessException;
import com.orchestration.common.util.JsonUtil;
import com.orchestration.approval.service.ApprovalService;
import com.orchestration.persistence.entity.ApprovalFlow;
import com.orchestration.persistence.entity.ApprovalInstance;
import com.orchestration.persistence.entity.ApprovalTask;
import com.orchestration.persistence.mapper.ApprovalFlowMapper;
import com.orchestration.persistence.mapper.ApprovalInstanceMapper;
import com.orchestration.persistence.mapper.ApprovalTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalServiceImpl implements ApprovalService {

    private final ApprovalFlowMapper flowMapper;
    private final ApprovalInstanceMapper instanceMapper;
    private final ApprovalTaskMapper taskMapper;

    @Override
    public Long createFlow(ApprovalFlow flow) {
        flowMapper.insert(flow);
        return flow.getId();
    }

    @Override
    public boolean updateFlow(ApprovalFlow flow) {
        return flowMapper.updateById(flow) > 0;
    }

    @Override
    public ApprovalFlow getFlow(Long id) {
        return flowMapper.selectById(id);
    }

    @Override
    public List<ApprovalFlow> listFlows(String flowType) {
        return flowMapper.selectList(
                new LambdaQueryWrapper<ApprovalFlow>()
                        .eq(flowType != null, ApprovalFlow::getFlowType, flowType)
                        .eq(ApprovalFlow::getEnabled, 1)
                        .orderByDesc(ApprovalFlow::getCreatedAt)
        );
    }

    @Override
    public boolean deleteFlow(Long id) {
        return flowMapper.deleteById(id) > 0;
    }

    @Override
    @Transactional
    public Long startInstance(String flowCode, String businessKey, Map<String, Object> businessData, Long initiatorId) {
        ApprovalFlow flow = flowMapper.selectOne(
                new LambdaQueryWrapper<ApprovalFlow>()
                        .eq(ApprovalFlow::getFlowCode, flowCode)
                        .eq(ApprovalFlow::getEnabled, 1)
        );
        if (flow == null) {
            throw new BusinessException("审批流程不存在或未启用");
        }

        ApprovalInstance instance = new ApprovalInstance();
        instance.setFlowId(flow.getId());
        instance.setBusinessKey(businessKey);
        instance.setBusinessData(businessData != null ? JsonUtil.toJson(businessData) : null);
        instance.setInitiatorId(initiatorId);
        instance.setStatus("processing");
        instance.setStartedAt(LocalDateTime.now());
        instanceMapper.insert(instance);

        createApprovalTasks(instance, flow);

        return instance.getId();
    }

    private void createApprovalTasks(ApprovalInstance instance, ApprovalFlow flow) {
        List<Map<String, Object>> nodes = parseFlowDefinition(flow.getFlowDefinition());
        if (nodes.isEmpty()) {
            throw new BusinessException("流程定义为空");
        }

        Map<String, Object> startNode = findStartNode(nodes);
        if (startNode == null) {
            throw new BusinessException("流程定义中没有开始节点");
        }

        String nextNodeId = getNextNodeId(startNode);
        createTasksForNode(instance, nextNodeId, nodes);
    }

    private List<Map<String, Object>> parseFlowDefinition(String flowDefinition) {
        try {
            Map<String, Object> def = JsonUtil.fromJson(flowDefinition, Map.class);
            return (List<Map<String, Object>>) def.getOrDefault("nodes", Collections.emptyList());
        } catch (Exception e) {
            log.error("解析流程定义失败", e);
            return Collections.emptyList();
        }
    }

    private Map<String, Object> findStartNode(List<Map<String, Object>> nodes) {
        for (Map<String, Object> node : nodes) {
            if ("start".equals(node.get("type"))) {
                return node;
            }
        }
        return null;
    }

    private String getNextNodeId(Map<String, Object> node) {
        List<Map<String, Object>> outgoing = (List<Map<String, Object>>) node.getOrDefault("outgoing", Collections.emptyList());
        if (!outgoing.isEmpty()) {
            return (String) outgoing.get(0).get("target");
        }
        return null;
    }

    private void createTasksForNode(ApprovalInstance instance, String nodeId, List<Map<String, Object>> nodes) {
        Map<String, Object> node = findNodeById(nodes, nodeId);
        if (node == null) {
            return;
        }

        String nodeType = (String) node.get("type");
        if ("end".equals(nodeType)) {
            completeInstance(instance);
            return;
        }

        if ("approval".equals(nodeType)) {
            createApprovalTask(instance, node);
        } else if ("condition".equals(nodeType)) {
            String nextNodeId = evaluateCondition(node, instance);
            createTasksForNode(instance, nextNodeId, nodes);
        } else if ("parallel".equals(nodeType)) {
            List<Map<String, Object>> outgoing = (List<Map<String, Object>>) node.getOrDefault("outgoing", Collections.emptyList());
            for (Map<String, Object> edge : outgoing) {
                createTasksForNode(instance, (String) edge.get("target"), nodes);
            }
        }

        instance.setCurrentNodeId(nodeId);
        instanceMapper.updateById(instance);
    }

    private Map<String, Object> findNodeById(List<Map<String, Object>> nodes, String nodeId) {
        for (Map<String, Object> node : nodes) {
            if (nodeId.equals(node.get("id"))) {
                return node;
            }
        }
        return null;
    }

    private void createApprovalTask(ApprovalInstance instance, Map<String, Object> node) {
        String approvalType = (String) node.getOrDefault("approvalType", "any");
        List<Long> approverIds = (List<Long>) node.getOrDefault("approvers", Collections.emptyList());

        if (approverIds.isEmpty()) {
            approverIds = resolveDynamicApprovers(node, instance);
        }

        for (Long approverId : approverIds) {
            ApprovalTask task = new ApprovalTask();
            task.setInstanceId(instance.getId());
            task.setNodeId((String) node.get("id"));
            task.setNodeName((String) node.get("name"));
            task.setApprovalType(approvalType);
            task.setAssigneeId(approverId);
            task.setStatus("pending");
            taskMapper.insert(task);
        }
    }

    private List<Long> resolveDynamicApprovers(Map<String, Object> node, ApprovalInstance instance) {
        String resolver = (String) node.get("approverResolver");
        if ("role_based".equals(resolver)) {
            String role = (String) node.get("role");
            return resolveApproversByRole(role);
        } else if ("leader_based".equals(resolver)) {
            return resolveApproversByLeader(instance.getInitiatorId());
        }
        return Collections.emptyList();
    }

    private List<Long> resolveApproversByRole(String role) {
        return Collections.singletonList(1L);
    }

    private List<Long> resolveApproversByLeader(Long userId) {
        return Collections.singletonList(2L);
    }

    private String evaluateCondition(Map<String, Object> node, ApprovalInstance instance) {
        String condition = (String) node.get("condition");
        Map<String, Object> businessData = instance.getBusinessData() != null
                ? JsonUtil.fromJson(instance.getBusinessData(), Map.class)
                : Collections.emptyMap();

        List<Map<String, Object>> outgoing = (List<Map<String, Object>>) node.getOrDefault("outgoing", Collections.emptyList());
        for (Map<String, Object> edge : outgoing) {
            String edgeCondition = (String) edge.get("condition");
            if (evaluateExpression(edgeCondition, businessData)) {
                return (String) edge.get("target");
            }
        }

        if (!outgoing.isEmpty()) {
            return (String) outgoing.get(outgoing.size() - 1).get("target");
        }
        return null;
    }

    private boolean evaluateExpression(String expression, Map<String, Object> data) {
        if (expression == null || expression.isEmpty()) {
            return true;
        }
        try {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String key = "${" + entry.getKey() + "}";
                if (expression.contains(key)) {
                    expression = expression.replace(key, String.valueOf(entry.getValue()));
                }
            }
            return evaluateSimpleExpression(expression);
        } catch (Exception e) {
            log.warn("条件表达式计算失败: {}", expression, e);
            return false;
        }
    }

    private boolean evaluateSimpleExpression(String expression) {
        if (expression.contains(">=")) {
            String[] parts = expression.split(">=");
            return Double.parseDouble(parts[0].trim()) >= Double.parseDouble(parts[1].trim());
        } else if (expression.contains("<=")) {
            String[] parts = expression.split("<=");
            return Double.parseDouble(parts[0].trim()) <= Double.parseDouble(parts[1].trim());
        } else if (expression.contains(">")) {
            String[] parts = expression.split(">");
            return Double.parseDouble(parts[0].trim()) > Double.parseDouble(parts[1].trim());
        } else if (expression.contains("<")) {
            String[] parts = expression.split("<");
            return Double.parseDouble(parts[0].trim()) < Double.parseDouble(parts[1].trim());
        } else if (expression.contains("==")) {
            String[] parts = expression.split("==");
            return parts[0].trim().equals(parts[1].trim().replace("'", ""));
        }
        return true;
    }

    private void completeInstance(ApprovalInstance instance) {
        instance.setStatus("approved");
        instance.setCompletedAt(LocalDateTime.now());
        instance.setResult("审批通过");
        instanceMapper.updateById(instance);
    }

    @Override
    public ApprovalInstance getInstance(Long id) {
        return instanceMapper.selectById(id);
    }

    @Override
    public List<ApprovalInstance> listInstances(Long initiatorId, String status) {
        return instanceMapper.selectList(
                new LambdaQueryWrapper<ApprovalInstance>()
                        .eq(initiatorId != null, ApprovalInstance::getInitiatorId, initiatorId)
                        .eq(status != null, ApprovalInstance::getStatus, status)
                        .orderByDesc(ApprovalInstance::getCreatedAt)
        );
    }

    @Override
    @Transactional
    public boolean approve(Long taskId, Long userId, String comment) {
        ApprovalTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("审批任务不存在");
        }
        if (!"pending".equals(task.getStatus())) {
            throw new BusinessException("审批任务已处理");
        }
        if (!userId.equals(task.getAssigneeId())) {
            throw new BusinessException("无权限审批此任务");
        }

        task.setStatus("approved");
        task.setComment(comment);
        task.setApprovedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        checkNodeCompletion(task);
        return true;
    }

    @Override
    @Transactional
    public boolean reject(Long taskId, Long userId, String comment) {
        ApprovalTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("审批任务不存在");
        }
        if (!"pending".equals(task.getStatus())) {
            throw new BusinessException("审批任务已处理");
        }
        if (!userId.equals(task.getAssigneeId())) {
            throw new BusinessException("无权限审批此任务");
        }

        task.setStatus("rejected");
        task.setComment(comment);
        task.setApprovedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        ApprovalInstance instance = instanceMapper.selectById(task.getInstanceId());
        instance.setStatus("rejected");
        instance.setCompletedAt(LocalDateTime.now());
        instance.setResult("审批拒绝: " + comment);
        instanceMapper.updateById(instance);

        return true;
    }

    private void checkNodeCompletion(ApprovalTask completedTask) {
        ApprovalInstance instance = instanceMapper.selectById(completedTask.getInstanceId());
        ApprovalFlow flow = flowMapper.selectById(instance.getFlowId());
        List<Map<String, Object>> nodes = parseFlowDefinition(flow.getFlowDefinition());

        List<ApprovalTask> nodeTasks = taskMapper.selectList(
                new LambdaQueryWrapper<ApprovalTask>()
                        .eq(ApprovalTask::getInstanceId, instance.getId())
                        .eq(ApprovalTask::getNodeId, completedTask.getNodeId())
        );

        String approvalType = completedTask.getApprovalType();
        boolean nodeCompleted = false;

        if ("any".equals(approvalType) || "or_sign".equals(approvalType)) {
            nodeCompleted = nodeTasks.stream().anyMatch(t -> "approved".equals(t.getStatus()));
        } else if ("all".equals(approvalType) || "countersign".equals(approvalType)) {
            nodeCompleted = nodeTasks.stream().allMatch(t -> "approved".equals(t.getStatus()));
        }

        if (nodeCompleted) {
            Map<String, Object> currentNode = findNodeById(nodes, completedTask.getNodeId());
            String nextNodeId = getNextNodeId(currentNode);
            createTasksForNode(instance, nextNodeId, nodes);
        }
    }

    @Override
    public boolean delegate(Long taskId, Long fromUserId, Long toUserId) {
        ApprovalTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("审批任务不存在");
        }
        if (!fromUserId.equals(task.getAssigneeId())) {
            throw new BusinessException("无权限转交此任务");
        }

        ApprovalTask newTask = new ApprovalTask();
        newTask.setInstanceId(task.getInstanceId());
        newTask.setNodeId(task.getNodeId());
        newTask.setNodeName(task.getNodeName());
        newTask.setApprovalType(task.getApprovalType());
        newTask.setAssigneeId(toUserId);
        newTask.setStatus("pending");
        taskMapper.insert(newTask);

        task.setStatus("delegated");
        taskMapper.updateById(task);

        return true;
    }

    @Override
    public boolean transfer(Long taskId, Long fromUserId, Long toUserId) {
        ApprovalTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("审批任务不存在");
        }
        if (!fromUserId.equals(task.getAssigneeId())) {
            throw new BusinessException("无权限转办此任务");
        }

        task.setAssigneeId(toUserId);
        task.setStatus("pending");
        return taskMapper.updateById(task) > 0;
    }

    @Override
    public boolean cancelInstance(Long instanceId, Long userId) {
        ApprovalInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new BusinessException("审批实例不存在");
        }
        if (!userId.equals(instance.getInitiatorId())) {
            throw new BusinessException("无权限取消此审批");
        }
        if (!Arrays.asList("pending", "processing").contains(instance.getStatus())) {
            throw new BusinessException("审批已完成，无法取消");
        }

        instance.setStatus("cancelled");
        instance.setCompletedAt(LocalDateTime.now());
        instance.setResult("用户取消");
        return instanceMapper.updateById(instance) > 0;
    }

    @Override
    public List<ApprovalTask> listUserTasks(Long userId, String status) {
        return taskMapper.selectList(
                new LambdaQueryWrapper<ApprovalTask>()
                        .eq(ApprovalTask::getAssigneeId, userId)
                        .eq(status != null, ApprovalTask::getStatus, status)
                        .orderByDesc(ApprovalTask::getCreatedAt)
        );
    }

    @Override
    public ApprovalTask getTask(Long id) {
        return taskMapper.selectById(id);
    }

    @Override
    public Map<String, Object> getFlowDiagram(Long instanceId) {
        ApprovalInstance instance = instanceMapper.selectById(instanceId);
        ApprovalFlow flow = flowMapper.selectById(instance.getFlowId());

        Map<String, Object> result = new HashMap<>();
        result.put("flowDefinition", flow.getFlowDefinition());
        result.put("currentNodeId", instance.getCurrentNodeId());
        result.put("instanceStatus", instance.getStatus());

        List<ApprovalTask> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<ApprovalTask>().eq(ApprovalTask::getInstanceId, instanceId)
        );
        result.put("tasks", tasks);

        return result;
    }

    @Override
    public boolean setDynamicApprovers(Long instanceId, String nodeId, List<Long> approverIds) {
        ApprovalInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new BusinessException("审批实例不存在");
        }

        List<ApprovalTask> existingTasks = taskMapper.selectList(
                new LambdaQueryWrapper<ApprovalTask>()
                        .eq(ApprovalTask::getInstanceId, instanceId)
                        .eq(ApprovalTask::getNodeId, nodeId)
                        .eq(ApprovalTask::getStatus, "pending")
        );

        for (ApprovalTask task : existingTasks) {
            taskMapper.deleteById(task);
        }

        for (Long approverId : approverIds) {
            ApprovalTask task = new ApprovalTask();
            task.setInstanceId(instanceId);
            task.setNodeId(nodeId);
            task.setNodeName("动态审批节点");
            task.setApprovalType("any");
            task.setAssigneeId(approverId);
            task.setStatus("pending");
            taskMapper.insert(task);
        }

        return true;
    }
}
