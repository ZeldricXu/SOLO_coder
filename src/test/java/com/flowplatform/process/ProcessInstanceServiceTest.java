package com.flowplatform.process;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowplatform.common.statemachine.FlowState;
import com.flowplatform.common.statemachine.FlowStateMachine;
import com.flowplatform.common.statemachine.NodeState;
import com.flowplatform.common.statemachine.TransitionResult;
import com.flowplatform.entity.ProcessInstance;
import com.flowplatform.entity.ProcessTask;
import com.flowplatform.mapper.ProcessInstanceMapper;
import com.flowplatform.mapper.ProcessTaskMapper;
import com.flowplatform.service.SysUserService;
import com.flowplatform.service.impl.ProcessInstanceServiceImpl;
import com.flowplatform.test.BaseUnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("流程实例Service测试")
public class ProcessInstanceServiceTest extends BaseUnitTest {

    @Mock
    private ProcessInstanceMapper instanceMapper;

    @Mock
    private ProcessTaskMapper taskMapper;

    @Mock
    private SysUserService userService;

    @Mock
    private FlowStateMachine flowStateMachine;

    @InjectMocks
    private ProcessInstanceServiceImpl processInstanceService;

    @BeforeEach
    void initBaseMapper() {
        ReflectionTestUtils.setField(processInstanceService, "baseMapper", instanceMapper);
    }

    @Test
    @DisplayName("待办任务查询测试")
    public void testGetPendingTasks() {
        Long userId = 1L;
        List<ProcessTask> mockTasks = List.of(
                createTask(1L, 100L, "部门审批", "PENDING"),
                createTask(2L, 101L, "财务审批", "PENDING")
        );

        when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(mockTasks);

        List<ProcessTask> tasks = processInstanceService.getPendingTasks(userId);

        assertNotNull(tasks);
        assertEquals(2, tasks.size());
        assertEquals("部门审批", tasks.get(0).getNodeName());
        verify(taskMapper, times(1)).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("已办任务查询测试")
    public void testGetCompletedTasks() {
        Long userId = 1L;
        List<ProcessTask> mockTasks = List.of(
                createTask(1L, 100L, "部门审批", "COMPLETED"),
                createTask(2L, 101L, "财务审批", "COMPLETED")
        );

        when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(mockTasks);

        List<ProcessTask> tasks = processInstanceService.getCompletedTasks(userId);

        assertNotNull(tasks);
        assertEquals(2, tasks.size());
        verify(taskMapper, times(1)).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("审批通过测试")
    public void testApproveTask() {
        Long taskId = 1L;
        Long userId = 2L;
        String comment = "同意";

        ProcessTask task = createTask(taskId, 100L, "部门审批", "PENDING");
        when(taskMapper.selectById(taskId)).thenReturn(task);
        when(taskMapper.updateById(any())).thenReturn(1);
        when(flowStateMachine.transitionNode(NodeState.PENDING, NodeState.APPROVED))
                .thenReturn(TransitionResult.ok(null, NodeState.APPROVED));
        when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(flowStateMachine.determineFlowStateFromNodes(any())).thenReturn(FlowState.PENDING);
        when(instanceMapper.selectById(100L)).thenReturn(createInstance(100L, "PENDING"));

        boolean result = processInstanceService.approveTask(taskId, userId, comment);

        assertTrue(result);
        assertEquals("COMPLETED", task.getStatus());
        assertEquals("APPROVE", task.getAction());
        assertNotNull(task.getCompleteTime());
        verify(taskMapper, times(1)).selectById(taskId);
        verify(taskMapper, times(1)).updateById(any());
        verify(flowStateMachine).transitionNode(NodeState.PENDING, NodeState.APPROVED);
        verify(flowStateMachine).determineFlowStateFromNodes(any());
    }

    @Test
    @DisplayName("拒绝审批测试")
    public void testRejectTask() {
        Long taskId = 1L;
        Long userId = 2L;
        String comment = "资料不全，拒绝";

        ProcessTask task = createTask(taskId, 100L, "部门审批", "PENDING");
        ProcessInstance instance = createInstance(100L, "PENDING");

        when(taskMapper.selectById(taskId)).thenReturn(task);
        when(taskMapper.updateById(any())).thenReturn(1);
        when(flowStateMachine.transitionNode(NodeState.PENDING, NodeState.REJECTED))
                .thenReturn(TransitionResult.ok(null, NodeState.REJECTED));
        when(instanceMapper.selectById(task.getInstanceId())).thenReturn(instance);
        when(flowStateMachine.resolveFlowStateAfterNodeAction("REJECT"))
                .thenReturn(FlowState.REJECTED);
        when(flowStateMachine.transition(FlowState.PENDING, FlowState.REJECTED))
                .thenReturn(TransitionResult.ok(FlowState.REJECTED, null));
        when(instanceMapper.updateById(any())).thenReturn(1);

        boolean result = processInstanceService.rejectTask(taskId, userId, comment);

        assertTrue(result);
        assertEquals("COMPLETED", task.getStatus());
        assertEquals("REJECTED", instance.getStatus());
        assertNotNull(instance.getEndTime());
        verify(taskMapper, times(1)).updateById(any());
        verify(instanceMapper, times(1)).updateById(any());
        verify(flowStateMachine).transitionNode(NodeState.PENDING, NodeState.REJECTED);
        verify(flowStateMachine).resolveFlowStateAfterNodeAction("REJECT");
        verify(flowStateMachine).transition(FlowState.PENDING, FlowState.REJECTED);
    }

    @Test
    @DisplayName("退回任务测试")
    public void testReturnTask() {
        Long taskId = 1L;
        Long userId = 2L;
        String comment = "退回修改";

        ProcessTask task = createTask(taskId, 100L, "部门审批", "PENDING");
        ProcessInstance instance = createInstance(100L, "PENDING");

        when(taskMapper.selectById(taskId)).thenReturn(task);
        when(taskMapper.updateById(any())).thenReturn(1);
        when(flowStateMachine.transitionNode(NodeState.PENDING, NodeState.RETURNED))
                .thenReturn(TransitionResult.ok(null, NodeState.RETURNED));
        when(instanceMapper.selectById(task.getInstanceId())).thenReturn(instance);
        when(flowStateMachine.resolveFlowStateAfterNodeAction("RETURN"))
                .thenReturn(FlowState.RETURNED);
        when(flowStateMachine.transition(FlowState.PENDING, FlowState.RETURNED))
                .thenReturn(TransitionResult.ok(FlowState.RETURNED, null));
        when(instanceMapper.updateById(any())).thenReturn(1);

        boolean result = processInstanceService.returnTask(taskId, userId, comment);

        assertTrue(result);
        assertEquals("COMPLETED", task.getStatus());
        assertEquals("RETURNED", instance.getStatus());
        verify(flowStateMachine).transitionNode(NodeState.PENDING, NodeState.RETURNED);
        verify(flowStateMachine).resolveFlowStateAfterNodeAction("RETURN");
        verify(flowStateMachine).transition(FlowState.PENDING, FlowState.RETURNED);
    }

    @Test
    @DisplayName("转交任务测试")
    public void testTransferTask() {
        Long taskId = 1L;
        Long fromUserId = 2L;
        Long toUserId = 3L;
        String comment = "请帮忙审批";

        ProcessTask task = createTask(taskId, 100L, "部门审批", "PENDING");
        when(taskMapper.selectById(taskId)).thenReturn(task);
        when(taskMapper.updateById(any())).thenReturn(1);
        when(flowStateMachine.transitionNode(NodeState.PENDING, NodeState.TRANSFERRED))
                .thenReturn(TransitionResult.ok(null, NodeState.TRANSFERRED));
        when(flowStateMachine.transitionNode(NodeState.TRANSFERRED, NodeState.PENDING))
                .thenReturn(TransitionResult.ok(null, NodeState.PENDING));

        boolean result = processInstanceService.transferTask(taskId, fromUserId, toUserId, comment);

        assertTrue(result);
        assertEquals(toUserId, task.getAssigneeId());
        verify(flowStateMachine).transitionNode(NodeState.PENDING, NodeState.TRANSFERRED);
        verify(flowStateMachine).transitionNode(NodeState.TRANSFERRED, NodeState.PENDING);
    }

    @Test
    @DisplayName("审批不存在的任务")
    public void testApproveNonExistentTask() {
        Long taskId = 999L;
        when(taskMapper.selectById(taskId)).thenReturn(null);

        boolean result = processInstanceService.approveTask(taskId, 1L, "同意");

        assertFalse(result);
        verify(taskMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("审批已完成的任务")
    public void testApproveCompletedTask() {
        Long taskId = 1L;
        ProcessTask task = createTask(taskId, 100L, "部门审批", "COMPLETED");
        when(taskMapper.selectById(taskId)).thenReturn(task);

        boolean result = processInstanceService.approveTask(taskId, 1L, "同意");

        assertFalse(result);
        verify(taskMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("加签任务测试")
    public void testAddSignTask() {
        Long taskId = 1L;
        Long signUserId = 5L;
        String comment = "请王总过目";

        ProcessTask originalTask = createTask(taskId, 100L, "部门审批", "PENDING");
        when(taskMapper.selectById(taskId)).thenReturn(originalTask);
        when(taskMapper.insert(any())).thenReturn(1);

        boolean result = processInstanceService.addSignTask(taskId, signUserId, comment);

        assertTrue(result);
        verify(taskMapper, times(1)).insert(any());
    }

    @Test
    @DisplayName("我发起的流程查询测试")
    public void testGetMyInstances() {
        Long userId = 1L;
        List<ProcessInstance> instances = List.of(
                createInstance(1L, "APPROVED"),
                createInstance(2L, "PENDING"),
                createInstance(3L, "REJECTED")
        );

        when(instanceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(instances);

        List<ProcessInstance> result = processInstanceService.getMyInstances(userId);

        assertEquals(3, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    private ProcessTask createTask(Long id, Long instanceId, String nodeName, String status) {
        ProcessTask task = new ProcessTask();
        task.setId(id);
        task.setInstanceId(instanceId);
        task.setNodeName(nodeName);
        task.setStatus(status);
        return task;
    }

    private ProcessInstance createInstance(Long id, String status) {
        ProcessInstance instance = new ProcessInstance();
        instance.setId(id);
        instance.setStatus(status);
        instance.setCreateTime(LocalDateTime.now());
        return instance;
    }
}
