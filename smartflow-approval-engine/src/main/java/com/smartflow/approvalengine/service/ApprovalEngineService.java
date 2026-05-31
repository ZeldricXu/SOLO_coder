package com.smartflow.approvalengine.service;

import com.smartflow.common.dto.ApprovalRequest;
import com.smartflow.common.enums.ApprovalStatus;
import com.smartflow.common.enums.ApprovalStrategy;
import com.smartflow.common.exception.BusinessException;
import com.smartflow.common.utils.IdGenerator;
import com.smartflow.common.utils.JsonUtils;
import com.smartflow.persistence.entity.ApprovalInstance;
import com.smartflow.persistence.entity.ApprovalProcess;
import com.smartflow.persistence.entity.ApprovalRecord;
import com.smartflow.persistence.mapper.ApprovalInstanceMapper;
import com.smartflow.persistence.mapper.ApprovalProcessMapper;
import com.smartflow.persistence.mapper.ApprovalRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ApprovalEngineService {

    private final ApprovalProcessMapper processMapper;
    private final ApprovalInstanceMapper instanceMapper;
    private final ApprovalRecordMapper recordMapper;

    @Transactional
    public Map<String, Object> startApproval(ApprovalRequest request) {
        ApprovalProcess process = processMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApprovalProcess>()
                .eq(ApprovalProcess::getProcessCode, request.getProcessId())
                .eq(ApprovalProcess::getEnabled, 1)
                .orderByDesc(ApprovalProcess::getVersion)
                .last("LIMIT 1")
        );

        if (process == null) {
            throw new BusinessException("审批流程不存在或未启用");
        }

        ApprovalInstance instance = createApprovalInstance(request, process);
        List<Map<String, Object>> nodes = parseNodeConfig(process.getNodeConfig());
        if (nodes.isEmpty()) {
            throw new BusinessException("审批流程没有配置节点");
        }

        Map<String, Object> firstNode = nodes.get(0);
        instance.setCurrentNodeId(Long.valueOf(firstNode.get("id").toString()));
        instance.setCurrentNodeName(firstNode.get("name").toString());
        instanceMapper.insert(instance);

        assignApprovers(instance, firstNode);

        Map<String, Object> result = new HashMap<>();
        result.put("instanceId", instance.getId());
        result.put("status", ApprovalStatus.PENDING.getCode());
        result.put("currentNode", firstNode);
        return result;
    }

    private ApprovalInstance createApprovalInstance(ApprovalRequest request, ApprovalProcess process) {
        ApprovalInstance instance = new ApprovalInstance();
        instance.setId(IdGenerator.generateId());
        instance.setProcessId(process.getProcessCode());
        instance.setProcessName(process.getProcessName());
        instance.setBusinessType(request.getBusinessType());
        instance.setBusinessId(request.getBusinessId());
        instance.setTitle(request.getTitle());
        instance.setContent(request.getContent());
        instance.setInitiatorId(request.getInitiatorId());
        instance.setVariables(JsonUtils.toJson(request.getVariables()));
        instance.setStatus(ApprovalStatus.PENDING.getCode());
        instance.setStartTime(LocalDateTime.now());
        return instance;
    }

    private List<Map<String, Object>> parseNodeConfig(String nodeConfig) {
        if (nodeConfig == null || nodeConfig.isEmpty()) {
            return Collections.emptyList();
        }
        return JsonUtils.parseList(nodeConfig, Map.class);
    }

    private void assignApprovers(ApprovalInstance instance, Map<String, Object> node) {
        Object approversObj = node.get("approvers");
        Integer strategy = (Integer) node.getOrDefault("strategy", ApprovalStrategy.ANY.getCode());
        List<Long> approverIds = new ArrayList<>();

        if (approversObj instanceof List) {
            approverIds = (List<Long>) approversObj;
        } else if (approversObj instanceof String) {
            approverIds = resolveDynamicApprovers((String) approversObj, instance);
        }

        for (int i = 0; i < approverIds.size(); i++) {
            ApprovalRecord record = new ApprovalRecord();
            record.setId(IdGenerator.generateId());
            record.setInstanceId(instance.getId());
            record.setNodeId(Long.valueOf(node.get("id").toString()));
            record.setNodeName(node.get("name").toString());
            record.setApproverId(approverIds.get(i));
            record.setStatus(ApprovalStatus.PENDING.getCode());
            record.setStrategy(strategy);
            record.setApproveOrder(i + 1);
            recordMapper.insert(record);
        }
    }

    private List<Long> resolveDynamicApprovers(String expression, ApprovalInstance instance) {
        List<Long> approverIds = new ArrayList<>();
        Map<String, Object> variables = instance.getVariables() != null 
            ? JsonUtils.parseMap(instance.getVariables()) 
            : Collections.emptyMap();

        if (expression.startsWith("role:")) {
            String role = expression.substring(5);
            approverIds = getApproversByRole(role, variables);
        } else if (expression.startsWith("dept:")) {
            String dept = expression.substring(5);
            approverIds = getApproversByDept(dept, variables);
        } else if (expression.startsWith("level:")) {
            int level = Integer.parseInt(expression.substring(6));
            approverIds = getApproversByLevel(level, variables);
        } else if (expression.equals("manager")) {
            approverIds = getInitiatorManager(instance.getInitiatorId());
        }

        return approverIds;
    }

    private List<Long> getApproversByRole(String role, Map<String, Object> variables) {
        List<Long> approvers = new ArrayList<>();
        approvers.add(1001L);
        approvers.add(1002L);
        return approvers;
    }

    private List<Long> getApproversByDept(String dept, Map<String, Object> variables) {
        List<Long> approvers = new ArrayList<>();
        approvers.add(2001L);
        return approvers;
    }

    private List<Long> getApproversByLevel(int level, Map<String, Object> variables) {
        List<Long> approvers = new ArrayList<>();
        approvers.add(3001L);
        return approvers;
    }

    private List<Long> getInitiatorManager(Long initiatorId) {
        List<Long> approvers = new ArrayList<>();
        approvers.add(initiatorId + 1000);
        return approvers;
    }

    @Transactional
    public Map<String, Object> approve(Long instanceId, Long approverId, Integer action, String comment) {
        ApprovalInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new BusinessException("审批实例不存在");
        }
        if (instance.getStatus() != ApprovalStatus.PENDING.getCode()) {
            throw new BusinessException("审批已完成或已取消");
        }

        List<ApprovalRecord> records = recordMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApprovalRecord>()
                .eq(ApprovalRecord::getInstanceId, instanceId)
                .eq(ApprovalRecord::getNodeId, instance.getCurrentNodeId())
        );

        if (records.isEmpty()) {
            throw new BusinessException("没有待审批的记录");
        }

        ApprovalRecord currentRecord = records.stream()
            .filter(r -> r.getApproverId().equals(approverId) && r.getAction() == null)
            .findFirst()
            .orElseThrow(() -> new BusinessException("您没有待审批的任务"));

        currentRecord.setAction(action);
        currentRecord.setComment(comment);
        currentRecord.setOperateTime(LocalDateTime.now());
        recordMapper.updateById(currentRecord);

        Integer strategy = records.get(0).getStrategy();
        return evaluateNodeResult(instance, records, strategy);
    }

    private Map<String, Object> evaluateNodeResult(ApprovalInstance instance, List<ApprovalRecord> records, Integer strategy) {
        Map<String, Object> result = new HashMap<>();
        result.put("instanceId", instance.getId());

        long approvedCount = records.stream().filter(r -> r.getAction() != null && r.getAction() == 1).count();
        long rejectedCount = records.stream().filter(r -> r.getAction() != null && r.getAction() == 2).count();
        long pendingCount = records.stream().filter(r -> r.getAction() == null).count();

        if (ApprovalStrategy.ANY.getCode().equals(strategy)) {
            if (approvedCount > 0) {
                return advanceToNextNode(instance, result);
            } else if (rejectedCount > 0) {
                return rejectApproval(instance, result);
            }
        } else if (ApprovalStrategy.ALL.getCode().equals(strategy)) {
            if (rejectedCount > 0) {
                return rejectApproval(instance, result);
            } else if (pendingCount == 0) {
                return advanceToNextNode(instance, result);
            }
        }

        result.put("status", ApprovalStatus.PENDING.getCode());
        result.put("message", "等待其他审批人处理");
        return result;
    }

    private Map<String, Object> advanceToNextNode(ApprovalInstance instance, Map<String, Object> result) {
        ApprovalProcess process = processMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApprovalProcess>()
                .eq(ApprovalProcess::getProcessCode, instance.getProcessId())
                .orderByDesc(ApprovalProcess::getVersion)
                .last("LIMIT 1")
        );

        List<Map<String, Object>> nodes = parseNodeConfig(process.getNodeConfig());
        int currentIndex = -1;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).get("id").toString().equals(instance.getCurrentNodeId().toString())) {
                currentIndex = i;
                break;
            }
        }

        if (currentIndex >= nodes.size() - 1) {
            instance.setStatus(ApprovalStatus.APPROVED.getCode());
            instance.setEndTime(LocalDateTime.now());
            instanceMapper.updateById(instance);
            result.put("status", ApprovalStatus.APPROVED.getCode());
            result.put("message", "审批已通过");
            return result;
        }

        Map<String, Object> nextNode = nodes.get(currentIndex + 1);
        instance.setCurrentNodeId(Long.valueOf(nextNode.get("id").toString()));
        instance.setCurrentNodeName(nextNode.get("name").toString());
        instanceMapper.updateById(instance);

        assignApprovers(instance, nextNode);

        result.put("status", ApprovalStatus.PENDING.getCode());
        result.put("nextNode", nextNode);
        result.put("message", "已进入下一审批节点");
        return result;
    }

    private Map<String, Object> rejectApproval(ApprovalInstance instance, Map<String, Object> result) {
        instance.setStatus(ApprovalStatus.REJECTED.getCode());
        instance.setEndTime(LocalDateTime.now());
        instanceMapper.updateById(instance);
        result.put("status", ApprovalStatus.REJECTED.getCode());
        result.put("message", "审批已拒绝");
        return result;
    }

    public Map<String, Object> getApprovalDetail(Long instanceId) {
        ApprovalInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new BusinessException("审批实例不存在");
        }

        List<ApprovalRecord> records = recordMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApprovalRecord>()
                .eq(ApprovalRecord::getInstanceId, instanceId)
                .orderByAsc(ApprovalRecord::getNodeId, ApprovalRecord::getApproveOrder)
        );

        Map<String, Object> result = new HashMap<>();
        result.put("instance", instance);
        result.put("records", records);
        return result;
    }
}
