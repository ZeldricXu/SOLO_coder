package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.model.Milestone;
import com.projmanage.model.Notification;
import com.projmanage.model.Task;
import com.projmanage.repository.MilestoneRepository;
import com.projmanage.repository.TaskRepository;
import com.projmanage.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MilestoneService 里程碑服务测试")
class MilestoneServiceTest {

    @Mock
    private MilestoneRepository milestoneRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private CollaborationService collaborationService;

    @InjectMocks
    private MilestoneService milestoneService;

    private Milestone milestone;
    private List<Task> milestoneTasks;

    @BeforeEach
    void setUp() {
        milestone = TestDataBuilder.aMilestone()
                .withId("milestone_001")
                .withProjectId("project_001")
                .withName("第一阶段完成")
                .withDate(LocalDate.now().plusDays(15))
                .withStatus(Constants.MILESTONE_STATUS_PENDING)
                .withProgress(0)
                .build();

        Task completedTask = TestDataBuilder.aTask()
                .withId("task_001")
                .withProjectId("project_001")
                .withMilestoneId("milestone_001")
                .withStatus(Constants.TASK_STATUS_COMPLETED)
                .withProgress(100)
                .build();

        Task inProgressTask = TestDataBuilder.aTask()
                .withId("task_002")
                .withProjectId("project_001")
                .withMilestoneId("milestone_001")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withProgress(50)
                .build();

        Task pendingTask = TestDataBuilder.aTask()
                .withId("task_003")
                .withProjectId("project_001")
                .withMilestoneId("milestone_001")
                .withStatus(Constants.TASK_STATUS_PENDING)
                .withProgress(0)
                .build();

        milestoneTasks = Arrays.asList(completedTask, inProgressTask, pendingTask);
    }

    @Test
    @DisplayName("里程碑创建测试 - 应正确创建里程碑")
    void testCreateMilestone() {
        when(milestoneRepository.save(any(Milestone.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Milestone created = milestoneService.createMilestone(
                "project_001",
                "测试里程碑",
                LocalDate.now().plusDays(30)
        );

        assertNotNull(created);
        assertEquals("project_001", created.getProjectId());
        assertEquals("测试里程碑", created.getMilestoneName());
        assertEquals(Constants.MILESTONE_STATUS_PENDING, created.getStatus());
        assertEquals(0, created.getProgress());

        verify(milestoneRepository, times(1)).save(any(Milestone.class));
    }

    @Test
    @DisplayName("里程碑创建测试 - 无截止日期的里程碑")
    void testCreateMilestoneWithoutDate() {
        when(milestoneRepository.save(any(Milestone.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Milestone created = milestoneService.createMilestone(
                "project_001",
                "无截止日期里程碑",
                null
        );

        assertNotNull(created);
        assertNull(created.getMilestoneDate());
    }

    @Test
    @DisplayName("里程碑进度更新测试 - 应正确计算平均进度")
    void testUpdateMilestoneProgress() {
        when(milestoneRepository.findById("milestone_001")).thenReturn(Optional.of(milestone));
        when(taskRepository.findByMilestoneId("milestone_001")).thenReturn(milestoneTasks);
        when(milestoneRepository.save(any(Milestone.class))).thenAnswer(invocation -> invocation.getArgument(0));

        milestoneService.updateMilestoneProgress("milestone_001");

        ArgumentCaptor<Milestone> milestoneCaptor = ArgumentCaptor.forClass(Milestone.class);
        verify(milestoneRepository, times(1)).save(milestoneCaptor.capture());

        Milestone saved = milestoneCaptor.getValue();
        int expectedProgress = (100 + 50 + 0) / 3;
        assertEquals(expectedProgress, saved.getProgress());
        assertEquals(Constants.MILESTONE_STATUS_IN_PROGRESS, saved.getStatus());
    }

    @Test
    @DisplayName("里程碑进度更新测试 - 所有任务完成应标记里程碑为已完成")
    void testUpdateMilestoneProgressAllCompleted() {
        Task completed1 = TestDataBuilder.aTask()
                .withId("task_001")
                .withMilestoneId("milestone_001")
                .withStatus(Constants.TASK_STATUS_COMPLETED)
                .withProgress(100)
                .build();
        Task completed2 = TestDataBuilder.aTask()
                .withId("task_002")
                .withMilestoneId("milestone_001")
                .withStatus(Constants.TASK_STATUS_COMPLETED)
                .withProgress(100)
                .build();

        when(milestoneRepository.findById("milestone_001")).thenReturn(Optional.of(milestone));
        when(taskRepository.findByMilestoneId("milestone_001")).thenReturn(Arrays.asList(completed1, completed2));
        when(milestoneRepository.save(any(Milestone.class))).thenAnswer(invocation -> invocation.getArgument(0));

        milestoneService.updateMilestoneProgress("milestone_001");

        ArgumentCaptor<Milestone> milestoneCaptor = ArgumentCaptor.forClass(Milestone.class);
        verify(milestoneRepository, times(1)).save(milestoneCaptor.capture());

        Milestone saved = milestoneCaptor.getValue();
        assertEquals(100, saved.getProgress());
        assertEquals(Constants.MILESTONE_STATUS_COMPLETED, saved.getStatus());
    }

    @Test
    @DisplayName("里程碑进度更新测试 - 无任务的里程碑应保持0进度")
    void testUpdateMilestoneProgressNoTasks() {
        when(milestoneRepository.findById("milestone_001")).thenReturn(Optional.of(milestone));
        when(taskRepository.findByMilestoneId("milestone_001")).thenReturn(Collections.emptyList());
        when(milestoneRepository.save(any(Milestone.class))).thenAnswer(invocation -> invocation.getArgument(0));

        milestoneService.updateMilestoneProgress("milestone_001");

        ArgumentCaptor<Milestone> milestoneCaptor = ArgumentCaptor.forClass(Milestone.class);
        verify(milestoneRepository, times(1)).save(milestoneCaptor.capture());

        Milestone saved = milestoneCaptor.getValue();
        assertEquals(0, saved.getProgress());
        assertEquals(Constants.MILESTONE_STATUS_PENDING, saved.getStatus());
    }

    @Test
    @DisplayName("里程碑进度更新测试 - 不存在的里程碑不做操作")
    void testUpdateMilestoneProgressNotFound() {
        when(milestoneRepository.findById("milestone_999")).thenReturn(Optional.empty());

        milestoneService.updateMilestoneProgress("milestone_999");

        verify(milestoneRepository, never()).save(any());
        verify(taskRepository, never()).findByMilestoneId(any());
    }

    @Test
    @DisplayName("里程碑临近提醒测试 - 3天内应触发提醒")
    void testMilestoneReminderNearDeadline() {
        Milestone nearMilestone = TestDataBuilder.aMilestone()
                .withId("milestone_002")
                .withProjectId("project_001")
                .withName("即将到期的里程碑")
                .withDate(LocalDate.now().plusDays(2))
                .withStatus(Constants.MILESTONE_STATUS_IN_PROGRESS)
                .withProgress(60)
                .build();

        when(milestoneRepository.findById("milestone_002")).thenReturn(Optional.of(nearMilestone));
        when(taskRepository.findByMilestoneId("milestone_002")).thenReturn(milestoneTasks);
        when(milestoneRepository.save(any(Milestone.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(collaborationService.sendNotification(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Notification());

        milestoneService.updateMilestoneProgress("milestone_002");

        ArgumentCaptor<String> notifTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> notifTitleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> notifContentCaptor = ArgumentCaptor.forClass(String.class);

        verify(collaborationService, times(1)).sendNotification(
                anyString(), anyString(), isNull(),
                notifTypeCaptor.capture(),
                notifTitleCaptor.capture(),
                notifContentCaptor.capture()
        );

        assertEquals(Constants.NOTIFICATION_TYPE_MILESTONE_REMINDER, notifTypeCaptor.getValue());
        assertEquals("里程碑提醒", notifTitleCaptor.getValue());
        assertTrue(notifContentCaptor.getValue().contains("即将到期的里程碑"));
    }

    @Test
    @DisplayName("里程碑临近提醒测试 - 超过3天不应触发提醒")
    void testMilestoneReminderFarDeadline() {
        Milestone farMilestone = TestDataBuilder.aMilestone()
                .withId("milestone_003")
                .withProjectId("project_001")
                .withName("还早的里程碑")
                .withDate(LocalDate.now().plusDays(10))
                .withStatus(Constants.MILESTONE_STATUS_IN_PROGRESS)
                .withProgress(30)
                .build();

        when(milestoneRepository.findById("milestone_003")).thenReturn(Optional.of(farMilestone));
        when(taskRepository.findByMilestoneId("milestone_003")).thenReturn(milestoneTasks);
        when(milestoneRepository.save(any(Milestone.class))).thenAnswer(invocation -> invocation.getArgument(0));

        milestoneService.updateMilestoneProgress("milestone_003");

        verify(collaborationService, never()).sendNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("里程碑临近提醒测试 - 已完成的里程碑不应触发提醒")
    void testMilestoneReminderCompleted() {
        Milestone completedMilestone = TestDataBuilder.aMilestone()
                .withId("milestone_004")
                .withProjectId("project_001")
                .withName("已完成的里程碑")
                .withDate(LocalDate.now().plusDays(1))
                .withStatus(Constants.MILESTONE_STATUS_COMPLETED)
                .withProgress(100)
                .build();

        Task completedTask = TestDataBuilder.aTask()
                .withId("task_001")
                .withMilestoneId("milestone_004")
                .withStatus(Constants.TASK_STATUS_COMPLETED)
                .withProgress(100)
                .build();

        when(milestoneRepository.findById("milestone_004")).thenReturn(Optional.of(completedMilestone));
        when(taskRepository.findByMilestoneId("milestone_004")).thenReturn(Collections.singletonList(completedTask));
        when(milestoneRepository.save(any(Milestone.class))).thenAnswer(invocation -> invocation.getArgument(0));

        milestoneService.updateMilestoneProgress("milestone_004");

        verify(collaborationService, never()).sendNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("里程碑临近提醒测试 - 无截止日期的里程碑不应触发提醒")
    void testMilestoneReminderNoDate() {
        Milestone noDateMilestone = TestDataBuilder.aMilestone()
                .withId("milestone_005")
                .withProjectId("project_001")
                .withName("无截止日期里程碑")
                .withDate(null)
                .withStatus(Constants.MILESTONE_STATUS_IN_PROGRESS)
                .withProgress(50)
                .build();

        when(milestoneRepository.findById("milestone_005")).thenReturn(Optional.of(noDateMilestone));
        when(taskRepository.findByMilestoneId("milestone_005")).thenReturn(milestoneTasks);
        when(milestoneRepository.save(any(Milestone.class))).thenAnswer(invocation -> invocation.getArgument(0));

        milestoneService.updateMilestoneProgress("milestone_005");

        verify(collaborationService, never()).sendNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("任务分配到里程碑测试 - 应正确关联任务和里程碑")
    void testAssignTaskToMilestone() {
        when(milestoneRepository.findById("milestone_001")).thenReturn(Optional.of(milestone));

        Task unassignedTask = TestDataBuilder.aTask()
                .withId("task_004")
                .withProjectId("project_001")
                .withMilestoneId(null)
                .build();

        when(taskRepository.findById("task_004")).thenReturn(Optional.of(unassignedTask));
        when(milestoneRepository.save(any(Milestone.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        milestoneService.assignTaskToMilestone("milestone_001", "task_004");

        ArgumentCaptor<Milestone> milestoneCaptor = ArgumentCaptor.forClass(Milestone.class);
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);

        verify(milestoneRepository, times(1)).save(milestoneCaptor.capture());
        verify(taskRepository, times(1)).save(taskCaptor.capture());

        assertTrue(milestoneCaptor.getValue().getMilestoneTasks().contains("task_004"));
        assertEquals("milestone_001", taskCaptor.getValue().getMilestoneId());
    }

    @Test
    @DisplayName("任务分配到里程碑测试 - 不存在的里程碑不做操作")
    void testAssignTaskToMilestoneNotFound() {
        when(milestoneRepository.findById("milestone_999")).thenReturn(Optional.empty());

        milestoneService.assignTaskToMilestone("milestone_999", "task_001");

        verify(milestoneRepository, never()).save(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("任务分配到里程碑测试 - 不存在的任务不做操作")
    void testAssignTaskToMilestoneTaskNotFound() {
        when(milestoneRepository.findById("milestone_001")).thenReturn(Optional.of(milestone));
        when(taskRepository.findById("task_999")).thenReturn(Optional.empty());

        milestoneService.assignTaskToMilestone("milestone_001", "task_999");

        verify(milestoneRepository, never()).save(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("任务分配到里程碑测试 - 任务已存在不重复添加")
    void testAssignTaskToMilestoneAlreadyAssigned() {
        Milestone milestoneWithTasks = TestDataBuilder.aMilestone()
                .withId("milestone_001")
                .withProjectId("project_001")
                .withTasks(Arrays.asList("task_001", "task_002"))
                .build();

        Task alreadyAssignedTask = TestDataBuilder.aTask()
                .withId("task_001")
                .withProjectId("project_001")
                .withMilestoneId("milestone_001")
                .build();

        when(milestoneRepository.findById("milestone_001")).thenReturn(Optional.of(milestoneWithTasks));
        when(taskRepository.findById("task_001")).thenReturn(Optional.of(alreadyAssignedTask));
        when(milestoneRepository.save(any(Milestone.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        milestoneService.assignTaskToMilestone("milestone_001", "task_001");

        ArgumentCaptor<Milestone> milestoneCaptor = ArgumentCaptor.forClass(Milestone.class);
        verify(milestoneRepository, times(1)).save(milestoneCaptor.capture());

        long taskCount = milestoneCaptor.getValue().getMilestoneTasks().stream()
                .filter(t -> t.equals("task_001"))
                .count();
        assertEquals(1, taskCount);
    }

    @Test
    @DisplayName("里程碑查询测试 - 应返回项目的所有里程碑")
    void testGetMilestonesByProject() {
        Milestone milestone1 = TestDataBuilder.aMilestone()
                .withId("milestone_001")
                .withProjectId("project_001")
                .withName("里程碑1")
                .build();
        Milestone milestone2 = TestDataBuilder.aMilestone()
                .withId("milestone_002")
                .withProjectId("project_001")
                .withName("里程碑2")
                .build();

        when(milestoneRepository.findByProjectId("project_001"))
                .thenReturn(Arrays.asList(milestone1, milestone2));

        List<Milestone> milestones = milestoneService.getMilestonesByProject("project_001");

        assertEquals(2, milestones.size());
        verify(milestoneRepository, times(1)).findByProjectId("project_001");
    }

    @Test
    @DisplayName("里程碑查询测试 - 应返回指定的里程碑")
    void testGetMilestoneById() {
        when(milestoneRepository.findById("milestone_001")).thenReturn(Optional.of(milestone));

        Optional<Milestone> result = milestoneService.getMilestoneById("milestone_001");

        assertTrue(result.isPresent());
        assertEquals("milestone_001", result.get().getMilestoneId());
    }

    @Test
    @DisplayName("里程碑查询测试 - 不存在的里程碑返回空")
    void testGetMilestoneByIdNotFound() {
        when(milestoneRepository.findById("milestone_999")).thenReturn(Optional.empty());

        Optional<Milestone> result = milestoneService.getMilestoneById("milestone_999");

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("提醒通知内容测试 - 应包含正确的里程碑信息")
    void testReminderNotificationContent() {
        Milestone veryNearMilestone = TestDataBuilder.aMilestone()
                .withId("milestone_006")
                .withProjectId("project_001")
                .withName("紧急里程碑")
                .withDate(LocalDate.now().plusDays(1))
                .withStatus(Constants.MILESTONE_STATUS_IN_PROGRESS)
                .withProgress(40)
                .build();

        when(milestoneRepository.findById("milestone_006")).thenReturn(Optional.of(veryNearMilestone));
        when(taskRepository.findByMilestoneId("milestone_006")).thenReturn(milestoneTasks);
        when(milestoneRepository.save(any(Milestone.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(collaborationService.sendNotification(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Notification());

        milestoneService.updateMilestoneProgress("milestone_006");

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(collaborationService, times(1)).sendNotification(
                anyString(), eq("project_001"), isNull(),
                anyString(), anyString(),
                contentCaptor.capture()
        );

        String content = contentCaptor.getValue();
        assertTrue(content.contains("紧急里程碑"));
        assertTrue(content.contains("1 天"));
    }
}
