package com.formflow.service;

import com.formflow.builder.TestDataBuilder;
import com.formflow.entity.ApprovalTask;
import com.formflow.enums.TaskStatus;
import com.formflow.repository.ApprovalTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("催办服务测试")
class ReminderServiceTest {

    @Mock
    private ApprovalTaskRepository approvalTaskRepository;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ReminderService reminderService;

    private ApprovalTask pendingTask;
    private ApprovalTask overdueTask;
    private ApprovalTask completedTask;

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
        pendingTask.setDueTime(LocalDateTime.now().plusHours(12));
        pendingTask.setAssignedTime(LocalDateTime.now().minusHours(12));

        overdueTask = TestDataBuilder.buildApprovalTask(
                "task_overdue_001",
                "instance_test_002",
                "node_manager",
                "user_manager_02",
                "部门经理2",
                TaskStatus.PENDING
        );
        overdueTask.setDueTime(LocalDateTime.now().minusHours(12));
        overdueTask.setAssignedTime(LocalDateTime.now().minusHours(36));

        completedTask = TestDataBuilder.buildApprovalTask(
                "task_completed_001",
                "instance_test_003",
                "node_manager",
                "user_manager_03",
                "部门经理3",
                TaskStatus.COMPLETED
        );
        completedTask.setDueTime(LocalDateTime.now().plusHours(12));

        reminderService.clearAllHistory();

        ReflectionTestUtils.setField(reminderService, "reminderEnabled", true);
        ReflectionTestUtils.setField(reminderService, "reminderIntervalHours", 24);
        ReflectionTestUtils.setField(reminderService, "maxReminders", 3);
        ReflectionTestUtils.setField(reminderService, "escalationEnabled", true);
        ReflectionTestUtils.setField(reminderService, "escalationAfterHours", 48);
    }

    @AfterEach
    void tearDown() {
        reminderService.clearAllHistory();
    }

    @Test
    @DisplayName("测试催办检查 - 催办功能禁用")
    void testCheckAndSendReminders_Disabled() {
        ReflectionTestUtils.setField(reminderService, "reminderEnabled", false);

        when(approvalTaskRepository.findAll()).thenReturn(Collections.singletonList(overdueTask));

        reminderService.checkAndSendReminders();

        verify(notificationService, never()).sendReminderNotification(any(ApprovalTask.class));
    }

    @Test
    @DisplayName("测试是否应该发送催办 - 待处理且临近截止")
    void testShouldSendReminder_PendingAndNearDue() {
        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();
        task.setTaskStatus(TaskStatus.PENDING);
        task.setDueTime(LocalDateTime.now().plusHours(12));
        task.setAssignedTime(LocalDateTime.now().minusHours(12));

        boolean result = reminderService.shouldSendReminder(task);

        assertTrue(result);
    }

    @Test
    @DisplayName("测试是否应该发送催办 - 已完成任务不催办")
    void testShouldSendReminder_CompletedTask() {
        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();
        task.setTaskStatus(TaskStatus.COMPLETED);
        task.setDueTime(LocalDateTime.now().minusHours(24));

        boolean result = reminderService.shouldSendReminder(task);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试是否应该发送催办 - 已取消任务不催办")
    void testShouldSendReminder_CanceledTask() {
        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();
        task.setTaskStatus(TaskStatus.CANCELED);
        task.setDueTime(LocalDateTime.now().minusHours(24));

        boolean result = reminderService.shouldSendReminder(task);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试是否应该发送催办 - 无截止时间不催办")
    void testShouldSendReminder_NoDueTime() {
        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();
        task.setTaskStatus(TaskStatus.PENDING);
        task.setDueTime(null);

        boolean result = reminderService.shouldSendReminder(task);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试是否应该发送催办 - 距离截止时间还很远不催办")
    void testShouldSendReminder_FarFromDue() {
        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();
        task.setTaskStatus(TaskStatus.PENDING);
        task.setDueTime(LocalDateTime.now().plusHours(48));

        boolean result = reminderService.shouldSendReminder(task);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试是否应该发送催办 - 已超过最大催办次数")
    void testShouldSendReminder_MaxRemindersReached() {
        String taskId = "task_max_reminders";
        ApprovalTask task = TestDataBuilder.buildApprovalTask(
                taskId, "instance_test", "node_manager",
                "user_manager_01", "部门经理", TaskStatus.PENDING
        );
        task.setDueTime(LocalDateTime.now().plusHours(12));

        for (int i = 0; i < 3; i++) {
            reminderService.sendReminder(task);
        }

        boolean result = reminderService.shouldSendReminder(task);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试是否应该发送催办 - 距离上次催办不足间隔时间")
    void testShouldSendReminder_TooSoonSinceLastReminder() throws InterruptedException {
        String taskId = "task_too_soon";
        ApprovalTask task = TestDataBuilder.buildApprovalTask(
                taskId, "instance_test", "node_manager",
                "user_manager_01", "部门经理", TaskStatus.PENDING
        );
        task.setDueTime(LocalDateTime.now().plusHours(12));

        reminderService.sendReminder(task);

        boolean result = reminderService.shouldSendReminder(task);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试发送催办通知 - 成功")
    void testSendReminder_Success() {
        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));

        reminderService.sendReminder(task);

        verify(notificationService, times(1)).sendReminderNotification(task);
        assertEquals(1, reminderService.getReminderCount(task.getTaskId()));
    }

    @Test
    @DisplayName("测试发送催办通知 - 记录催办历史")
    void testSendReminder_RecordHistory() {
        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));

        reminderService.sendReminder(task);

        List<LocalDateTime> history = reminderService.getReminderHistory(task.getTaskId());
        assertNotNull(history);
        assertEquals(1, history.size());
    }

    @Test
    @DisplayName("测试发送催办通知 - 多次催办记录递增")
    void testSendReminder_MultipleReminders() {
        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));

        reminderService.sendReminder(task);
        reminderService.sendReminder(task);
        reminderService.sendReminder(task);

        assertEquals(3, reminderService.getReminderCount(task.getTaskId()));
        assertEquals(3, reminderService.getReminderHistory(task.getTaskId()).size());
    }

    @Test
    @DisplayName("测试是否应该升级 - 催办功能禁用")
    void testShouldEscalate_EscalationDisabled() {
        ReflectionTestUtils.setField(reminderService, "escalationEnabled", false);

        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();
        task.setTaskStatus(TaskStatus.PENDING);
        task.setAssignedTime(LocalDateTime.now().minusHours(72));

        boolean result = reminderService.shouldEscalate(task);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试是否应该升级 - 任务已完成")
    void testShouldEscalate_CompletedTask() {
        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();
        task.setTaskStatus(TaskStatus.COMPLETED);
        task.setAssignedTime(LocalDateTime.now().minusHours(72));

        boolean result = reminderService.shouldEscalate(task);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试是否应该升级 - 时间不足")
    void testShouldEscalate_NotEnoughTimePassed() {
        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();
        task.setTaskStatus(TaskStatus.PENDING);
        task.setAssignedTime(LocalDateTime.now().minusHours(24));

        boolean result = reminderService.shouldEscalate(task);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试是否应该升级 - 时间足够但催办次数不足")
    void testShouldEscalate_NotEnoughReminders() {
        String taskId = "task_escalation_test";
        ApprovalTask task = TestDataBuilder.buildApprovalTask(
                taskId, "instance_test", "node_manager",
                "user_manager_01", "部门经理", TaskStatus.PENDING
        );
        task.setAssignedTime(LocalDateTime.now().minusHours(72));

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));
        reminderService.sendReminder(task);

        boolean result = reminderService.shouldEscalate(task);

        assertFalse(result);
    }

    @Test
    @DisplayName("测试是否应该升级 - 满足所有条件")
    void testShouldEscalate_AllConditionsMet() {
        String taskId = "task_escalation_ready";
        ApprovalTask task = TestDataBuilder.buildApprovalTask(
                taskId, "instance_test", "node_manager",
                "user_manager_01", "部门经理", TaskStatus.PENDING
        );
        task.setAssignedTime(LocalDateTime.now().minusHours(72));

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));

        for (int i = 0; i < 3; i++) {
            reminderService.sendReminder(task);
        }

        boolean result = reminderService.shouldEscalate(task);

        assertTrue(result);
    }

    @Test
    @DisplayName("测试执行升级 - 成功")
    void testPerformEscalation_Success() {
        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();
        task.setInstanceId("instance_escalation_001");

        reminderService.setEscalationApprovers("instance_escalation_001",
                Arrays.asList("user_director_01", "user_director_02"));

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));

        reminderService.performEscalation(task);

        verify(notificationService, times(2)).sendReminderNotification(any(ApprovalTask.class));
    }

    @Test
    @DisplayName("测试执行升级 - 使用默认升级审批人")
    void testPerformEscalation_DefaultApprovers() {
        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();
        task.setInstanceId("instance_escalation_no_config");

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));

        reminderService.performEscalation(task);

        verify(notificationService, times(1)).sendReminderNotification(any(ApprovalTask.class));
    }

    @Test
    @DisplayName("测试清除催办历史 - 单个任务")
    void testClearReminderHistory_SingleTask() {
        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));
        reminderService.sendReminder(task);
        reminderService.sendReminder(task);

        assertEquals(2, reminderService.getReminderCount(task.getTaskId()));

        reminderService.clearReminderHistory(task.getTaskId());

        assertEquals(0, reminderService.getReminderCount(task.getTaskId()));
        assertTrue(reminderService.getReminderHistory(task.getTaskId()).isEmpty());
    }

    @Test
    @DisplayName("测试清除催办历史 - 所有任务")
    void testClearAllHistory() {
        ApprovalTask task1 = TestDataBuilder.buildApprovalTask(
                "task_1", "instance_1", "node_1", "user_1", "用户1", TaskStatus.PENDING
        );
        ApprovalTask task2 = TestDataBuilder.buildApprovalTask(
                "task_2", "instance_2", "node_2", "user_2", "用户2", TaskStatus.PENDING
        );

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));

        reminderService.sendReminder(task1);
        reminderService.sendReminder(task2);

        assertEquals(1, reminderService.getReminderCount("task_1"));
        assertEquals(1, reminderService.getReminderCount("task_2"));

        reminderService.clearAllHistory();

        assertEquals(0, reminderService.getReminderCount("task_1"));
        assertEquals(0, reminderService.getReminderCount("task_2"));
    }

    @Test
    @DisplayName("测试催办间隔控制 - 24小时间隔")
    void testReminderInterval_24Hours() {
        ReflectionTestUtils.setField(reminderService, "reminderIntervalHours", 24);

        String taskId = "task_interval_test";
        ApprovalTask task = TestDataBuilder.buildApprovalTask(
                taskId, "instance_test", "node_manager",
                "user_manager_01", "部门经理", TaskStatus.PENDING
        );
        task.setDueTime(LocalDateTime.now().plusHours(12));

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));

        reminderService.sendReminder(task);

        boolean shouldSendNow = reminderService.shouldSendReminder(task);
        assertFalse(shouldSendNow, "距离上次催办不足24小时，不应该发送");
    }

    @Test
    @DisplayName("测试催办间隔控制 - 缩短间隔测试")
    void testReminderInterval_ShortInterval() {
        ReflectionTestUtils.setField(reminderService, "reminderIntervalHours", 1);

        String taskId = "task_short_interval";
        ApprovalTask task = TestDataBuilder.buildApprovalTask(
                taskId, "instance_test", "node_manager",
                "user_manager_01", "部门经理", TaskStatus.PENDING
        );
        task.setDueTime(LocalDateTime.now().plusHours(12));

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));

        reminderService.sendReminder(task);

        boolean shouldSendNow = reminderService.shouldSendReminder(task);
        assertFalse(shouldSendNow, "距离上次催办不足1小时，不应该发送");
    }

    @Test
    @DisplayName("测试重复催办抑制 - 达到最大次数后不再发送")
    void testRepeatedReminderSuppression_MaxReached() {
        ReflectionTestUtils.setField(reminderService, "maxReminders", 3);

        String taskId = "task_suppression";
        ApprovalTask task = TestDataBuilder.buildApprovalTask(
                taskId, "instance_test", "node_manager",
                "user_manager_01", "部门经理", TaskStatus.PENDING
        );
        task.setDueTime(LocalDateTime.now().plusHours(12));

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));

        for (int i = 0; i < 3; i++) {
            reminderService.sendReminder(task);
        }

        boolean shouldSend = reminderService.shouldSendReminder(task);

        assertFalse(shouldSend, "已达到最大催办次数，应该被抑制");
        assertEquals(3, reminderService.getReminderCount(taskId));
    }

    @Test
    @DisplayName("测试催办超时后自动升级处理")
    void testAutoEscalationAfterTimeout() {
        String taskId = "task_auto_escalation";
        ApprovalTask task = TestDataBuilder.buildApprovalTask(
                taskId, "instance_auto_esc", "node_manager",
                "user_manager_01", "部门经理", TaskStatus.PENDING
        );
        task.setDueTime(LocalDateTime.now().minusHours(72));
        task.setAssignedTime(LocalDateTime.now().minusHours(96));

        ReflectionTestUtils.setField(reminderService, "reminderIntervalHours", 1);
        ReflectionTestUtils.setField(reminderService, "escalationAfterHours", 48);

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));

        for (int i = 0; i < 3; i++) {
            reminderService.sendReminder(task);
        }

        boolean shouldEscalate = reminderService.shouldEscalate(task);

        assertTrue(shouldEscalate, "超时后应该自动升级");
    }

    @Test
    @DisplayName("测试催办检查 - 处理多个待处理任务")
    void testCheckAndSendReminders_MultipleTasks() {
        List<ApprovalTask> tasks = new ArrayList<>();

        ApprovalTask task1 = TestDataBuilder.buildApprovalTask(
                "task_check_1", "instance_1", "node_1",
                "user_1", "用户1", TaskStatus.PENDING
        );
        task1.setDueTime(LocalDateTime.now().plusHours(12));
        tasks.add(task1);

        ApprovalTask task2 = TestDataBuilder.buildApprovalTask(
                "task_check_2", "instance_2", "node_2",
                "user_2", "用户2", TaskStatus.PENDING
        );
        task2.setDueTime(LocalDateTime.now().plusHours(12));
        tasks.add(task2);

        ApprovalTask completed = TestDataBuilder.buildApprovalTask(
                "task_check_3", "instance_3", "node_3",
                "user_3", "用户3", TaskStatus.COMPLETED
        );
        tasks.add(completed);

        when(approvalTaskRepository.findAll()).thenReturn(tasks);
        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));

        reminderService.checkAndSendReminders();

        verify(notificationService, times(2)).sendReminderNotification(any(ApprovalTask.class));
    }

    @Test
    @DisplayName("测试催办检查 - 无待处理任务")
    void testCheckAndSendReminders_NoPendingTasks() {
        List<ApprovalTask> tasks = new ArrayList<>();

        ApprovalTask completed1 = TestDataBuilder.buildApprovalTask(
                "task_none_1", "instance_1", "node_1",
                "user_1", "用户1", TaskStatus.COMPLETED
        );
        tasks.add(completed1);

        ApprovalTask canceled = TestDataBuilder.buildApprovalTask(
                "task_none_2", "instance_2", "node_2",
                "user_2", "用户2", TaskStatus.CANCELED
        );
        tasks.add(canceled);

        when(approvalTaskRepository.findAll()).thenReturn(tasks);

        reminderService.checkAndSendReminders();

        verify(notificationService, never()).sendReminderNotification(any(ApprovalTask.class));
    }

    @Test
    @DisplayName("测试获取催办计数 - 无历史返回0")
    void testGetReminderCount_NoHistory() {
        int count = reminderService.getReminderCount("nonexistent_task");

        assertEquals(0, count);
    }

    @Test
    @DisplayName("测试获取催办历史 - 无历史返回空列表")
    void testGetReminderHistory_NoHistory() {
        List<LocalDateTime> history = reminderService.getReminderHistory("nonexistent_task");

        assertNotNull(history);
        assertTrue(history.isEmpty());
    }

    @Test
    @DisplayName("测试设置升级审批人")
    void testSetEscalationApprovers() {
        String instanceId = "instance_custom_esc";
        List<String> customApprovers = Arrays.asList("user_custom_1", "user_custom_2", "user_custom_3");

        reminderService.setEscalationApprovers(instanceId, customApprovers);

        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();
        task.setInstanceId(instanceId);

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));
        reminderService.performEscalation(task);

        verify(notificationService, times(3)).sendReminderNotification(any(ApprovalTask.class));
    }

    @Test
    @DisplayName("测试催办配置动态修改")
    void testDynamicConfigurationChanges() {
        ApprovalTask task = TestDataBuilder.buildBasicApprovalTask();
        task.setDueTime(LocalDateTime.now().plusHours(12));

        doNothing().when(notificationService).sendReminderNotification(any(ApprovalTask.class));

        ReflectionTestUtils.setField(reminderService, "maxReminders", 5);
        for (int i = 0; i < 5; i++) {
            reminderService.sendReminder(task);
        }

        boolean shouldSend = reminderService.shouldSendReminder(task);
        assertFalse(shouldSend);
        assertEquals(5, reminderService.getReminderCount(task.getTaskId()));
    }
}
