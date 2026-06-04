package com.flowplatform.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flowplatform.common.WorkingHoursCalculator;
import com.flowplatform.common.statemachine.FlowState;
import com.flowplatform.common.statemachine.FlowStateMachine;
import com.flowplatform.common.statemachine.NodeState;
import com.flowplatform.common.statemachine.TransitionResult;
import com.flowplatform.entity.*;
import com.flowplatform.mapper.ProcessInstanceMapper;
import com.flowplatform.mapper.ProcessTaskMapper;
import com.flowplatform.service.ProcessInstanceService;
import com.flowplatform.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.DoubleSummaryStatistics;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessInstanceServiceImpl extends ServiceImpl<ProcessInstanceMapper, ProcessInstance>
        implements ProcessInstanceService {

    private final ProcessTaskMapper taskMapper;
    private final SysUserService userService;
    private final FlowStateMachine flowStateMachine;
    private final WorkingHoursCalculator workingHoursCalculator;

    @Override
    @Transactional
    public ProcessInstance startProcess(Long processId, Long initiatorId, String title, String formData) {
        ProcessInstance instance = new ProcessInstance();
        instance.setProcessId(processId);
        instance.setTitle(title);
        instance.setInitiatorId(initiatorId);
        instance.setFormData(formData);
        instance.setStatus(FlowState.DRAFT.name());
        instance.setStartTime(LocalDateTime.now());
        instance.setCurrentNodes("[\"start\"]");
        applyTransition(instance, FlowState.PENDING);
        save(instance);
        return instance;
    }

    @Override
    @Transactional
    public boolean approveTask(Long taskId, Long userId, String comment) {
        ProcessTask task = taskMapper.selectById(taskId);
        if (task == null || !"PENDING".equals(task.getStatus())) return false;

        TransitionResult nodeResult = flowStateMachine.transitionNode(NodeState.PENDING, NodeState.APPROVED);
        if (!nodeResult.isSuccess()) {
            log.warn("节点状态转移失败: {}", nodeResult.getErrorMessage());
            return false;
        }

        task.setStatus("COMPLETED");
        task.setAction("APPROVE");
        task.setComment(comment);
        task.setAssigneeId(userId);
        task.setCompleteTime(LocalDateTime.now());
        taskMapper.updateById(task);
        updateInstanceStatus(task.getInstanceId());
        return true;
    }

    @Override
    @Transactional
    public boolean rejectTask(Long taskId, Long userId, String comment) {
        ProcessTask task = taskMapper.selectById(taskId);
        if (task == null || !"PENDING".equals(task.getStatus())) return false;

        TransitionResult nodeResult = flowStateMachine.transitionNode(NodeState.PENDING, NodeState.REJECTED);
        if (!nodeResult.isSuccess()) {
            log.warn("节点状态转移失败: {}", nodeResult.getErrorMessage());
            return false;
        }

        task.setStatus("COMPLETED");
        task.setAction("REJECT");
        task.setComment(comment);
        task.setAssigneeId(userId);
        task.setCompleteTime(LocalDateTime.now());
        taskMapper.updateById(task);

        ProcessInstance instance = getById(task.getInstanceId());
        applyTransition(instance, flowStateMachine.resolveFlowStateAfterNodeAction("REJECT"));
        instance.setEndTime(LocalDateTime.now());
        updateById(instance);
        return true;
    }

    @Override
    @Transactional
    public boolean returnTask(Long taskId, Long userId, String comment) {
        ProcessTask task = taskMapper.selectById(taskId);
        if (task == null || !"PENDING".equals(task.getStatus())) return false;

        TransitionResult nodeResult = flowStateMachine.transitionNode(NodeState.PENDING, NodeState.RETURNED);
        if (!nodeResult.isSuccess()) {
            log.warn("节点状态转移失败: {}", nodeResult.getErrorMessage());
            return false;
        }

        task.setStatus("COMPLETED");
        task.setAction("RETURN");
        task.setComment(comment);
        task.setAssigneeId(userId);
        task.setCompleteTime(LocalDateTime.now());
        taskMapper.updateById(task);

        ProcessInstance instance = getById(task.getInstanceId());
        applyTransition(instance, flowStateMachine.resolveFlowStateAfterNodeAction("RETURN"));
        updateById(instance);
        return true;
    }

    @Override
    @Transactional
    public boolean transferTask(Long taskId, Long fromUserId, Long toUserId, String comment) {
        ProcessTask task = taskMapper.selectById(taskId);
        if (task == null || !"PENDING".equals(task.getStatus())) return false;

        TransitionResult nodeResult = flowStateMachine.transitionNode(NodeState.PENDING, NodeState.TRANSFERRED);
        if (!nodeResult.isSuccess()) {
            log.warn("节点状态转移失败: {}", nodeResult.getErrorMessage());
            return false;
        }

        flowStateMachine.transitionNode(NodeState.TRANSFERRED, NodeState.PENDING);
        task.setAssigneeId(toUserId);
        task.setComment(comment);
        taskMapper.updateById(task);
        return true;
    }

    @Override
    @Transactional
    public boolean addSignTask(Long taskId, Long userId, String comment) {
        ProcessTask originalTask = taskMapper.selectById(taskId);
        if (originalTask == null) return false;
        ProcessTask signTask = new ProcessTask();
        signTask.setInstanceId(originalTask.getInstanceId());
        signTask.setProcessId(originalTask.getProcessId());
        signTask.setNodeId(originalTask.getNodeId() + "_sign_" + System.currentTimeMillis());
        signTask.setNodeName("加签审批");
        signTask.setNodeType("SIGN");
        signTask.setAssigneeId(userId);
        signTask.setStatus("PENDING");
        signTask.setComment(comment);
        taskMapper.insert(signTask);
        return true;
    }

    // Concurrent-safe pending task query. FOR UPDATE SKIP LOCKED ensures each task is only fetched by one request. Locked tasks are skipped rather than causing the query to block. Requires MySQL 8.0+ with InnoDB engine.
    @Override
    @Transactional(readOnly = true)
    public List<ProcessTask> getPendingTasks(Long userId) {
        List<ProcessTask> tasks = taskMapper.selectPendingByUserIdForUpdate(userId);
        tasks.sort(Comparator.comparing(ProcessTask::getCreateTime).reversed());
        return tasks;
    }

    @Override
    public List<ProcessTask> getCompletedTasks(Long userId) {
        return taskMapper.selectList(new LambdaQueryWrapper<ProcessTask>()
                .eq(ProcessTask::getAssigneeId, userId)
                .eq(ProcessTask::getStatus, "COMPLETED")
                .orderByDesc(ProcessTask::getCompleteTime));
    }

    @Override
    public List<ProcessInstance> getMyInstances(Long userId) {
        return list(new LambdaQueryWrapper<ProcessInstance>()
                .eq(ProcessInstance::getInitiatorId, userId)
                .orderByDesc(ProcessInstance::getCreateTime));
    }

    @Override
    public boolean urgeInstance(Long instanceId, Long userId) {
        return true;
    }

    @Override
    public List<Map<String, Object>> getStatusStats() {
        return baseMapper.countByStatus();
    }

    @Override
    public List<Map<String, Object>> getDateTrend() {
        return baseMapper.countByDateRecent30Days();
    }

    @Override
    public Map<String, Object> getAvgApprovalTime() {
        List<ProcessInstance> completed = list(new LambdaQueryWrapper<ProcessInstance>()
                .eq(ProcessInstance::getStatus, "APPROVED")
                .eq(ProcessInstance::getDeleted, 0)
                .isNotNull(ProcessInstance::getEndTime)
                .select(ProcessInstance::getStartTime, ProcessInstance::getEndTime));

        if (completed.isEmpty()) {
            return Map.of("avg_hours", 0.0);
        }

        double totalHours = 0.0;
        int count = 0;
        for (ProcessInstance inst : completed) {
            double hours = workingHoursCalculator.calculateWorkingHours(inst.getStartTime(), inst.getEndTime());
            if (hours > 0) {
                totalHours += hours;
                count++;
            }
        }

        double avg = count > 0 ? Math.round((totalHours / count) * 100) / 100.0 : 0.0;
        return Map.of("avg_hours", avg);
    }

    @Override
    public List<Map<String, Object>> getNodeAvgTime() {
        List<ProcessTask> completedTasks = taskMapper.selectList(new LambdaQueryWrapper<ProcessTask>()
                .eq(ProcessTask::getStatus, "COMPLETED")
                .isNotNull(ProcessTask::getCompleteTime)
                .select(ProcessTask::getNodeName, ProcessTask::getCreateTime, ProcessTask::getCompleteTime));

        Map<String, DoubleSummaryStatistics> nodeStats = new HashMap<>();
        for (ProcessTask task : completedTasks) {
            String nodeName = task.getNodeName();
            if (nodeName == null) continue;

            double hours = workingHoursCalculator.calculateWorkingHours(task.getCreateTime(), task.getCompleteTime());
            if (hours <= 0) continue;

            nodeStats.computeIfAbsent(nodeName, k -> new DoubleSummaryStatistics())
                    .accept(hours);
        }

        return nodeStats.entrySet().stream()
                .map(e -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("node_name", e.getKey());
                    double avg = Math.round(e.getValue().getAverage() * 100) / 100.0;
                    map.put("avg_hours", avg);
                    return map;
                })
                .sorted((a, b) -> Double.compare((Double) b.get("avg_hours"), (Double) a.get("avg_hours")))
                .limit(10)
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getFormRanking() {
        return baseMapper.countByFormTop10();
    }

    private void updateInstanceStatus(Long instanceId) {
        List<ProcessTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<ProcessTask>()
                .eq(ProcessTask::getInstanceId, instanceId));
        List<String> nodeStatuses = tasks.stream()
                .map(ProcessTask::getAction)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        FlowState newFlowState = flowStateMachine.determineFlowStateFromNodes(nodeStatuses);
        ProcessInstance instance = getById(instanceId);
        FlowState currentState = FlowState.valueOf(instance.getStatus());

        if (currentState != newFlowState) {
            applyTransition(instance, newFlowState);
            if (newFlowState == FlowState.APPROVED || newFlowState == FlowState.COMPLETED) {
                instance.setEndTime(LocalDateTime.now());
            }
            updateById(instance);
        }
    }

    private void applyTransition(ProcessInstance instance, FlowState targetState) {
        FlowState currentState = FlowState.valueOf(instance.getStatus());
        TransitionResult result = flowStateMachine.transition(currentState, targetState);
        if (result.isSuccess()) {
            instance.setStatus(result.getNewFlowState().name());
            log.info("流程状态转移成功: instanceId={} {} → {}", instance.getId(), currentState, result.getNewFlowState());
        } else {
            log.error("流程状态转移失败: instanceId={} {} → {}: {}", instance.getId(), currentState, targetState, result.getErrorMessage());
            throw new IllegalStateException(result.getErrorMessage());
        }
    }
}
