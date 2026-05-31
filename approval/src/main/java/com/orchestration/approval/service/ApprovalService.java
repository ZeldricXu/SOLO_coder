package com.orchestration.approval.service;

import com.orchestration.persistence.entity.ApprovalFlow;
import com.orchestration.persistence.entity.ApprovalInstance;
import com.orchestration.persistence.entity.ApprovalTask;
import java.util.List;
import java.util.Map;

public interface ApprovalService {

    Long createFlow(ApprovalFlow flow);

    boolean updateFlow(ApprovalFlow flow);

    ApprovalFlow getFlow(Long id);

    List<ApprovalFlow> listFlows(String flowType);

    boolean deleteFlow(Long id);

    Long startInstance(String flowCode, String businessKey, Map<String, Object> businessData, Long initiatorId);

    ApprovalInstance getInstance(Long id);

    List<ApprovalInstance> listInstances(Long initiatorId, String status);

    boolean approve(Long taskId, Long userId, String comment);

    boolean reject(Long taskId, Long userId, String comment);

    boolean delegate(Long taskId, Long fromUserId, Long toUserId);

    boolean transfer(Long taskId, Long fromUserId, Long toUserId);

    boolean cancelInstance(Long instanceId, Long userId);

    List<ApprovalTask> listUserTasks(Long userId, String status);

    ApprovalTask getTask(Long id);

    Map<String, Object> getFlowDiagram(Long instanceId);

    boolean setDynamicApprovers(Long instanceId, String nodeId, List<Long> approverIds);
}
