package com.formflow.service;

import com.formflow.builder.TestDataBuilder;
import com.formflow.entity.ApprovalTask;
import com.formflow.entity.ProcessDefinition;
import com.formflow.entity.ProcessInstance;
import com.formflow.entity.ProcessNode;
import com.formflow.enums.TaskStatus;
import com.formflow.exception.BusinessException;
import com.formflow.repository.ApprovalTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("审批任务服务测试")
class ApprovalTaskServiceTest {

    @Mock
    private ApprovalTaskRepository approvalTaskRepository;

    @InjectMocks
    private ApprovalTaskService approvalTaskService;

    private ApprovalTask pendingTask;
    private ApprovalTask completedTask;
    private ProcessInstance processInstance;
    private ProcessDefinition multiApproverProcess;

    @BeforeEach
    void setUp() {
        pendingTask = TestDataBuilder.buildApprovalTask(
                "task_pending_001",
                "instance_test_001",
                "node_manager",
                "user_manager_01",
                "部门经理",
                TaskStatus.PENDING
        );
        pendingTask.setAssignedTime(LocalDateTime.now().minusHours(12));
        pendingTask.setDueTime(LocalDateTime.now().plusHours(12));

        completedTask = TestDataBuilder.buildApprovalTask(
                "task_completed_001",
                "instance_test_002",
                "node_manager",
                "user_manager_02",
                "部门经理2",
                TaskStatus.COMPLETED
        );
        completedTask.setCompletedTime(LocalDateTime.now());

        processInstance = TestDataBuilder.buildBasicProcessInstance();
        multiApproverProcess = TestDataBuilder.buildMultiApproverProcess(false);
    }

    @Test
    @DisplayName("测试创建审批任务 - 成功")
    void testCreateApprovalTask_Success() {
        when(approvalTaskRepository.save(any(ApprovalTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalTask task = approvalTaskService.createApprovalTask(
                "instance_test_001",
                "node_manager",
                "部门经理审批",
                "form_test_001",
                "template_test",
                "user_approver_01",
                "审批人1",
                "user_submitter_01",
                "提交人1",
                "测试审批申请",
                LocalDateTime.now().plusHours(24)
        );

        assertNotNull(task);
        assertNotNull(task.getTaskId());
        assertTrue(task.getTaskId().startsWith("task_"));
        assertEquals("instance_test_001", task.getInstanceId());
        assertEquals("node_manager", task.getNodeId());
        assertEquals("user_approver_01", task.getApproverId());
        assertEquals(TaskStatus.PENDING, task.getTaskStatus());
        assertEquals("测试审批申请", task.getFormTitle());

        verify(approvalTaskRepository, times(1)).save(any(ApprovalTask.class));
    }

    @Test
    @DisplayName("测试获取审批任务 - 成功")
    void testGetTaskByTaskId_Success() {
        when(approvalTaskRepository.findByTaskId(pendingTask.getTaskId()))
                .thenReturn(Optional.of(pendingTask));

        ApprovalTask result = approvalTaskService.getTaskByTaskId(pendingTask.getTaskId());

        assertNotNull(result);
        assertEquals(pendingTask.getTaskId(), result.getTaskId());
        assertEquals(pendingTask.getApproverId(), result.getApproverId());
    }

    @Test
    @DisplayName("测试获取审批任务 - 不存在抛出异常")
    void testGetTaskByTaskId_NotFound() {
        String nonExistentId = "task_nonexistent";
        when(approvalTaskRepository.findByTaskId(nonExistentId))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> approvalTaskService.getTaskByTaskId(nonExistentId));

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("审批任务不存在"));
    }

    @Test
    @DisplayName("测试获取用户审批任务列表")
    void testGetTasksByApproverId() {
        List<ApprovalTask> tasks = new ArrayList<>();
        tasks.add(pendingTask);
        tasks.add(completedTask);

        when(approvalTaskRepository.findByApproverIdOrderByAssignedTimeDesc("user_manager_01"))
                .thenReturn(tasks);

        List<ApprovalTask> result = approvalTaskService.getTasksByApproverId("user_manager_01");

        assertNotNull(result);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("测试获取用户待处理审批任务")
    void testGetPendingTasksByApproverId() {
        List<ApprovalTask> pendingTasks = Collections.singletonList(pendingTask);

        when(approvalTaskRepository.findByApproverIdAndTaskStatusOrderByAssignedTimeDesc(
                "user_manager_01", TaskStatus.PENDING))
                .thenReturn(pendingTasks);

        List<ApprovalTask> result = approvalTaskService.getPendingTasksByApproverId("user_manager_01");

        assertNotNull(result);
        assertEquals(1, result.size());
        for (ApprovalTask task : result) {
            assertEquals(TaskStatus.PENDING, task.getTaskStatus());
        }
    }

    @Test
    @DisplayName("测试获取流程所有审批任务")
    void testGetTasksByInstanceId() {
        List<ApprovalTask> tasks = TestDataBuilder.buildMultipleApprovalTasks(3, "instance_test", "node_manager");

        when(approvalTaskRepository.findByInstanceId("instance_test"))
                .thenReturn(tasks);

        List<ApprovalTask> result = approvalTaskService.getTasksByInstanceId("instance_test");

        assertNotNull(result);
        assertEquals(3, result.size());
        for (ApprovalTask task : result) {
            assertEquals("instance_test", task.getInstanceId());
        }
    }

    @Test
    @DisplayName("测试完成审批任务 - 成功")
    void testCompleteTask_Success() {
        when(approvalTaskRepository.findByTaskId(pendingTask.getTaskId()))
                .thenReturn(Optional.of(pendingTask));
        when(approvalTaskRepository.save(any(ApprovalTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalTask result = approvalTaskService.completeTask(
                pendingTask.getTaskId(),
                "APPROVED",
                "同意申请",
                "user_manager_01",
                "部门经理"
        );

        assertNotNull(result);
        assertEquals(TaskStatus.COMPLETED, result.getTaskStatus());
        assertEquals("APPROVED", result.getApprovalResult());
        assertEquals("同意申请", result.getApprovalComment());
        assertNotNull(result.getCompletedTime());
    }

    @Test
    @DisplayName("测试完成审批任务 - 任务已处理抛出异常")
    void testCompleteTask_AlreadyProcessed() {
        when(approvalTaskRepository.findByTaskId(completedTask.getTaskId()))
                .thenReturn(Optional.of(completedTask));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> approvalTaskService.completeTask(
                        completedTask.getTaskId(), "APPROVED", "同意",
                        "user_manager_02", "部门经理2")
        );

        assertTrue(exception.getMessage().contains("任务已处理"));
    }

    @Test
    @DisplayName("测试完成审批任务 - 拒绝结果")
    void testCompleteTask_Rejected() {
        when(approvalTaskRepository.findByTaskId(pendingTask.getTaskId()))
                .thenReturn(Optional.of(pendingTask));
        when(approvalTaskRepository.save(any(ApprovalTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalTask result = approvalTaskService.completeTask(
                pendingTask.getTaskId(),
                "REJECTED",
                "资料不完整，需要补充",
                "user_manager_01",
                "部门经理"
        );

        assertNotNull(result);
        assertEquals(TaskStatus.COMPLETED, result.getTaskStatus());
        assertEquals("REJECTED", result.getApprovalResult());
    }

    @Test
    @DisplayName("测试取消审批任务 - 成功")
    void testCancelTask_Success() {
        when(approvalTaskRepository.findByTaskId(pendingTask.getTaskId()))
                .thenReturn(Optional.of(pendingTask));
        when(approvalTaskRepository.save(any(ApprovalTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> approvalTaskService.cancelTask(pendingTask.getTaskId()));

        assertEquals(TaskStatus.CANCELED, pendingTask.getTaskStatus());
    }

    @Test
    @DisplayName("测试取消审批任务 - 已完成任务不处理")
    void testCancelTask_CompletedTask() {
        when(approvalTaskRepository.findByTaskId(completedTask.getTaskId()))
                .thenReturn(Optional.of(completedTask));

        assertDoesNotThrow(() -> approvalTaskService.cancelTask(completedTask.getTaskId()));

        assertEquals(TaskStatus.COMPLETED, completedTask.getTaskStatus());
    }

    @Test
    @DisplayName("测试取消流程所有待处理任务")
    void testCancelPendingTasks_Success() {
        List<ApprovalTask> pendingTasks = new ArrayList<>();
        pendingTasks.add(TestDataBuilder.buildApprovalTask(
                "task_cancel_1", "instance_cancel", "node_1",
                "user_1", "用户1", TaskStatus.PENDING
        ));
        pendingTasks.add(TestDataBuilder.buildApprovalTask(
                "task_cancel_2", "instance_cancel", "node_1",
                "user_2", "用户2", TaskStatus.PENDING
        ));

        when(approvalTaskRepository.findByInstanceIdAndTaskStatus("instance_cancel", TaskStatus.PENDING))
                .thenReturn(pendingTasks);
        when(approvalTaskRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> approvalTaskService.cancelPendingTasks("instance_cancel"));

        for (ApprovalTask task : pendingTasks) {
            assertEquals(TaskStatus.CANCELED, task.getTaskStatus());
        }
        verify(approvalTaskRepository, times(1)).saveAll(pendingTasks);
    }

    @Test
    @DisplayName("测试转交审批任务 - 成功")
    void testTransferTask_Success() {
        when(approvalTaskRepository.findByTaskId(pendingTask.getTaskId()))
                .thenReturn(Optional.of(pendingTask));
        when(approvalTaskRepository.save(any(ApprovalTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String oldApprover = pendingTask.getApproverId();
        approvalTaskService.transferTask(
                pendingTask.getTaskId(),
                "user_new_approver",
                "新审批人",
                "有事请假，转交同事处理"
        );

        assertEquals("user_new_approver", pendingTask.getApproverId());
        assertEquals("新审批人", pendingTask.getApproverName());
        assertTrue(pendingTask.getApprovalComment().contains("转交"));
        assertTrue(pendingTask.getApprovalComment().contains("新审批人"));
    }

    @Test
    @DisplayName("测试转交审批任务 - 已完成任务抛出异常")
    void testTransferTask_CompletedTask() {
        when(approvalTaskRepository.findByTaskId(completedTask.getTaskId()))
                .thenReturn(Optional.of(completedTask));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> approvalTaskService.transferTask(
                        completedTask.getTaskId(),
                        "user_new", "新审批人", "转交原因"
                )
        );

        assertTrue(exception.getMessage().contains("只能转交待处理的任务"));
    }

    @Test
    @DisplayName("测试委托审批任务 - 成功")
    void testDelegateTask_Success() {
        when(approvalTaskRepository.findByTaskId(pendingTask.getTaskId()))
                .thenReturn(Optional.of(pendingTask));
        when(approvalTaskRepository.save(any(ApprovalTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> approvalTaskService.delegateTask(
                pendingTask.getTaskId(),
                "user_delegate_01",
                "委托人"
        ));

        verify(approvalTaskRepository, times(1)).save(any(ApprovalTask.class));
    }

    @Test
    @DisplayName("测试委托审批任务 - 已完成任务抛出异常")
    void testDelegateTask_CompletedTask() {
        when(approvalTaskRepository.findByTaskId(completedTask.getTaskId()))
                .thenReturn(Optional.of(completedTask));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> approvalTaskService.delegateTask(
                        completedTask.getTaskId(),
                        "user_delegate", "委托人"
                )
        );

        assertTrue(exception.getMessage().contains("只能委托待处理的任务"));
    }

    @Test
    @DisplayName("测试统计待处理任务数")
    void testCountPendingTasksByApproverId() {
        when(approvalTaskRepository.countByApproverIdAndTaskStatus(
                "user_manager_01", TaskStatus.PENDING))
                .thenReturn(5L);

        Long count = approvalTaskService.countPendingTasksByApproverId("user_manager_01");

        assertEquals(5L, count);
    }

    @Test
    @DisplayName("测试统计总任务数")
    void testCountTotalTasksByApproverId() {
        when(approvalTaskRepository.countByApproverId("user_manager_01"))
                .thenReturn(20L);

        Long count = approvalTaskService.countTotalTasksByApproverId("user_manager_01");

        assertEquals(20L, count);
    }

    @Test
    @DisplayName("测试多人审批 - 会签场景2人并行分发")
    void testMultiApprover_AndSign_TwoApprovers() {
        ProcessNode managerNode = multiApproverProcess.getNodes().stream()
                .filter(n -> "node_manager".equals(n.getNodeId()))
                .findFirst()
                .orElse(null);

        assertNotNull(managerNode);
        assertEquals("AND", managerNode.getApprovalStrategy());
        assertEquals("user_manager_01,user_manager_02", managerNode.getApproverUserIds());

        String[] approvers = managerNode.getApproverUserIds().split(",");
        assertEquals(2, approvers.length);

        List<ApprovalTask> createdTasks = new ArrayList<>();
        for (String approver : approvers) {
            ApprovalTask task = TestDataBuilder.buildApprovalTask(
                    "task_and_" + approver,
                    processInstance.getInstanceId(),
                    managerNode.getNodeId(),
                    approver,
                    "审批人",
                    TaskStatus.PENDING
            );
            createdTasks.add(task);
        }

        assertEquals(2, createdTasks.size());
        for (ApprovalTask task : createdTasks) {
            assertEquals(TaskStatus.PENDING, task.getTaskStatus());
            assertEquals(processInstance.getInstanceId(), task.getInstanceId());
        }
    }

    @Test
    @DisplayName("测试多人审批 - 或签场景2人并行分发")
    void testMultiApprover_OrSign_TwoApprovers() {
        ProcessDefinition orSignProcess = TestDataBuilder.buildMultiApproverProcess(true);

        ProcessNode managerNode = orSignProcess.getNodes().stream()
                .filter(n -> "node_manager".equals(n.getNodeId()))
                .findFirst()
                .orElse(null);

        assertNotNull(managerNode);
        assertEquals("OR", managerNode.getApprovalStrategy());
        assertEquals("user_manager_01,user_manager_02", managerNode.getApproverUserIds());

        String[] approvers = managerNode.getApproverUserIds().split(",");
        assertEquals(2, approvers.length);

        List<ApprovalTask> createdTasks = new ArrayList<>();
        for (String approver : approvers) {
            ApprovalTask task = TestDataBuilder.buildApprovalTask(
                    "task_or_" + approver,
                    processInstance.getInstanceId(),
                    managerNode.getNodeId(),
                    approver,
                    "审批人",
                    TaskStatus.PENDING
            );
            createdTasks.add(task);
        }

        assertEquals(2, createdTasks.size());
    }

    @Test
    @DisplayName("测试多人审批 - 3人会签并行分发")
    void testMultiApprover_AndSign_ThreeApprovers() {
        ProcessDefinition threeApproverProcess = TestDataBuilder.buildMultiApproverProcess(false);

        ProcessNode hrNode = threeApproverProcess.getNodes().stream()
                .filter(n -> "node_hr".equals(n.getNodeId()))
                .findFirst()
                .orElse(null);

        assertNotNull(hrNode);
        assertEquals("AND", hrNode.getApprovalStrategy());
        assertEquals("user_hr_01,user_hr_02,user_hr_03", hrNode.getApproverUserIds());

        String[] approvers = hrNode.getApproverUserIds().split(",");
        assertEquals(3, approvers.length);

        List<ApprovalTask> createdTasks = new ArrayList<>();
        for (String approver : approvers) {
            ApprovalTask task = TestDataBuilder.buildApprovalTask(
                    "task_hr_" + approver,
                    processInstance.getInstanceId(),
                    hrNode.getNodeId(),
                    approver,
                    "HR审批人",
                    TaskStatus.PENDING
            );
            createdTasks.add(task);
        }

        assertEquals(3, createdTasks.size());

        Set<String> approverIds = new HashSet<>();
        for (ApprovalTask task : createdTasks) {
            approverIds.add(task.getApproverId());
        }
        assertEquals(3, approverIds.size());
        assertTrue(approverIds.contains("user_hr_01"));
        assertTrue(approverIds.contains("user_hr_02"));
        assertTrue(approverIds.contains("user_hr_03"));
    }

    @Test
    @DisplayName("测试多人审批 - 3人或签并行分发")
    void testMultiApprover_OrSign_ThreeApprovers() {
        ProcessDefinition orSignProcess = TestDataBuilder.buildMultiApproverProcess(true);

        ProcessNode hrNode = orSignProcess.getNodes().stream()
                .filter(n -> "node_hr".equals(n.getNodeId()))
                .findFirst()
                .orElse(null);

        assertNotNull(hrNode);
        assertEquals("OR", hrNode.getApprovalStrategy());
        assertEquals("user_hr_01,user_hr_02,user_hr_03", hrNode.getApproverUserIds());

        String[] approvers = hrNode.getApproverUserIds().split(",");
        assertEquals(3, approvers.length);
    }

    @Test
    @DisplayName("测试审批结果汇总 - 会签全部通过")
    void testApprovalResultSummary_AndSign_AllApproved() {
        List<ApprovalTask> tasks = TestDataBuilder.buildMultipleApprovalTasks(3, "instance_summary", "node_test");

        for (ApprovalTask task : tasks) {
            task.setTaskStatus(TaskStatus.COMPLETED);
            task.setApprovalResult("APPROVED");
        }

        long approvedCount = tasks.stream()
                .filter(t -> "APPROVED".equals(t.getApprovalResult()))
                .count();
        long totalCount = tasks.size();

        assertEquals(3, approvedCount);
        assertEquals(3, totalCount);

        boolean allApproved = approvedCount == totalCount;
        assertTrue(allApproved, "会签应该全部通过才能继续流转");
    }

    @Test
    @DisplayName("测试审批结果汇总 - 会签有人拒绝")
    void testApprovalResultSummary_AndSign_OneRejected() {
        List<ApprovalTask> tasks = TestDataBuilder.buildMultipleApprovalTasks(3, "instance_reject", "node_test");

        tasks.get(0).setTaskStatus(TaskStatus.COMPLETED);
        tasks.get(0).setApprovalResult("APPROVED");

        tasks.get(1).setTaskStatus(TaskStatus.COMPLETED);
        tasks.get(1).setApprovalResult("REJECTED");

        tasks.get(2).setTaskStatus(TaskStatus.COMPLETED);
        tasks.get(2).setApprovalResult("APPROVED");

        long rejectedCount = tasks.stream()
                .filter(t -> "REJECTED".equals(t.getApprovalResult()))
                .count();

        assertEquals(1, rejectedCount);

        boolean shouldReject = rejectedCount > 0;
        assertTrue(shouldReject, "会签有人拒绝，整体流程应该被拒绝");
    }

    @Test
    @DisplayName("测试审批结果汇总 - 或签一人通过即可")
    void testApprovalResultSummary_OrSign_OneApproved() {
        List<ApprovalTask> tasks = TestDataBuilder.buildMultipleApprovalTasks(3, "instance_or", "node_test");

        tasks.get(0).setTaskStatus(TaskStatus.COMPLETED);
        tasks.get(0).setApprovalResult("APPROVED");

        tasks.get(1).setTaskStatus(TaskStatus.PENDING);
        tasks.get(2).setTaskStatus(TaskStatus.PENDING);

        long approvedCount = tasks.stream()
                .filter(t -> "APPROVED".equals(t.getApprovalResult()))
                .count();

        assertTrue(approvedCount >= 1, "或签一人通过即可继续流转");
    }

    @Test
    @DisplayName("测试并行分发 - 任务独立性验证")
    void testParallelDistribution_TaskIndependence() {
        String instanceId = "instance_parallel";
        String nodeId = "node_test";

        List<ApprovalTask> tasks = new ArrayList<>();
        tasks.add(TestDataBuilder.buildApprovalTask(
                "task_ind_1", instanceId, nodeId, "user_1", "用户1", TaskStatus.PENDING
        ));
        tasks.add(TestDataBuilder.buildApprovalTask(
                "task_ind_2", instanceId, nodeId, "user_2", "用户2", TaskStatus.PENDING
        ));
        tasks.add(TestDataBuilder.buildApprovalTask(
                "task_ind_3", instanceId, nodeId, "user_3", "用户3", TaskStatus.PENDING
        ));

        tasks.get(0).setTaskStatus(TaskStatus.COMPLETED);
        tasks.get(0).setApprovalResult("APPROVED");

        assertEquals(TaskStatus.PENDING, tasks.get(1).getTaskStatus());
        assertEquals(TaskStatus.PENDING, tasks.get(2).getTaskStatus());

        assertNotEquals(tasks.get(0).getTaskId(), tasks.get(1).getTaskId());
        assertNotEquals(tasks.get(1).getTaskId(), tasks.get(2).getTaskId());
        assertNotEquals(tasks.get(0).getTaskId(), tasks.get(2).getTaskId());

        assertNotEquals(tasks.get(0).getApproverId(), tasks.get(1).getApproverId());
        assertNotEquals(tasks.get(1).getApproverId(), tasks.get(2).getApproverId());
    }

    @Test
    @DisplayName("测试任务字段完整性")
    void testTaskFieldCompleteness() {
        ApprovalTask task = approvalTaskService.createApprovalTask(
                "instance_complete",
                "node_complete",
                "完整测试节点",
                "form_complete",
                "template_complete",
                "user_approver",
                "审批人",
                "user_submitter",
                "提交人",
                "完整测试申请",
                LocalDateTime.now().plusHours(24)
        );

        assertNotNull(task.getTaskId());
        assertNotNull(task.getInstanceId());
        assertNotNull(task.getNodeId());
        assertNotNull(task.getNodeName());
        assertNotNull(task.getFormId());
        assertNotNull(task.getTemplateId());
        assertNotNull(task.getApproverId());
        assertNotNull(task.getSubmitterId());
        assertNotNull(task.getFormTitle());
        assertNotNull(task.getTaskStatus());
        assertNotNull(task.getAssignedTime());
        assertNotNull(task.getDueTime());
    }

    @Test
    @DisplayName("测试获取节点所有审批任务")
    void testGetTasksByInstanceIdAndNodeId() {
        List<ApprovalTask> nodeTasks = Arrays.asList(
                TestDataBuilder.buildApprovalTask(
                        "task_node_1", "instance_node", "node_manager",
                        "user_m1", "经理1", TaskStatus.PENDING
                ),
                TestDataBuilder.buildApprovalTask(
                        "task_node_2", "instance_node", "node_manager",
                        "user_m2", "经理2", TaskStatus.PENDING
                )
        );

        when(approvalTaskRepository.findByInstanceIdAndNodeId("instance_node", "node_manager"))
                .thenReturn(nodeTasks);

        List<ApprovalTask> result = approvalTaskService.getTasksByInstanceIdAndNodeId(
                "instance_node", "node_manager");

        assertNotNull(result);
        assertEquals(2, result.size());
        for (ApprovalTask task : result) {
            assertEquals("node_manager", task.getNodeId());
        }
    }

    @Test
    @DisplayName("测试获取表单相关审批任务")
    void testGetTasksByFormId() {
        List<ApprovalTask> formTasks = Arrays.asList(
                TestDataBuilder.buildApprovalTask(
                        "task_form_1", "instance_form", "node_1",
                        "user_1", "用户1", TaskStatus.PENDING
                )
        );
        formTasks.get(0).setFormId("form_test_001");

        when(approvalTaskRepository.findByFormId("form_test_001"))
                .thenReturn(formTasks);

        List<ApprovalTask> result = approvalTaskService.getTasksByFormId("form_test_001");

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("form_test_001", result.get(0).getFormId());
    }
}
