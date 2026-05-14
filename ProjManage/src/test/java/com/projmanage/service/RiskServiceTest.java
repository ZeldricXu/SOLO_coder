package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.model.Notification;
import com.projmanage.model.Risk;
import com.projmanage.model.Task;
import com.projmanage.repository.RiskRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RiskService 风险检测服务测试")
class RiskServiceTest {

    @Mock
    private RiskRepository riskRepository;

    @Mock
    private CollaborationService collaborationService;

    @InjectMocks
    private RiskService riskService;

    private Task normalTask;
    private Task urgentTask;
    private Task overdueTask;

    @BeforeEach
    void setUp() {
        normalTask = TestDataBuilder.aTask()
                .withId("task_001")
                .withProjectId("project_001")
                .withName("正常任务")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withProgress(80)
                .withDueDate(LocalDate.now().plusDays(10))
                .build();

        urgentTask = TestDataBuilder.aTask()
                .withId("task_002")
                .withProjectId("project_001")
                .withName("紧急任务")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withProgress(30)
                .withDueDate(LocalDate.now().plusDays(2))
                .build();

        overdueTask = TestDataBuilder.aTask()
                .withId("task_003")
                .withProjectId("project_001")
                .withName("已延期任务")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withProgress(50)
                .withDueDate(LocalDate.now().minusDays(2))
                .build();
    }

    @Test
    @DisplayName("风险创建测试 - 应正确创建风险记录")
    void testCreateRisk() {
        when(riskRepository.save(any(Risk.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(collaborationService.sendNotification(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Notification());

        Risk createdRisk = riskService.createRisk(
                "project_001",
                "task_001",
                Constants.RISK_TYPE_SCHEDULE_DELAY,
                "测试风险描述",
                Constants.RISK_LEVEL_HIGH
        );

        assertNotNull(createdRisk);
        assertEquals("project_001", createdRisk.getProjectId());
        assertEquals("task_001", createdRisk.getTaskId());
        assertEquals(Constants.RISK_TYPE_SCHEDULE_DELAY, createdRisk.getRiskType());
        assertEquals(Constants.RISK_LEVEL_HIGH, createdRisk.getRiskLevel());
        assertEquals(Constants.RISK_STATUS_IDENTIFIED, createdRisk.getRiskStatus());
        assertNotNull(createdRisk.getIdentifiedAt());
        assertNull(createdRisk.getResolvedAt());

        verify(riskRepository, times(1)).save(any(Risk.class));
        verify(collaborationService, times(1)).sendNotification(
                eq("system"), eq("project_001"), eq("task_001"),
                eq(Constants.NOTIFICATION_TYPE_RISK_ALERT), anyString(), anyString());
    }

    @Test
    @DisplayName("风险级别判断测试 - 已延期任务应检测为高风险")
    void testCheckTaskRiskOverdue() {
        when(riskRepository.save(any(Risk.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(collaborationService.sendNotification(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Notification());

        riskService.checkTaskRisk(overdueTask);

        ArgumentCaptor<Risk> riskCaptor = ArgumentCaptor.forClass(Risk.class);
        verify(riskRepository, times(1)).save(riskCaptor.capture());

        Risk savedRisk = riskCaptor.getValue();
        assertEquals(Constants.RISK_TYPE_SCHEDULE_DELAY, savedRisk.getRiskType());
        assertEquals(Constants.RISK_LEVEL_HIGH, savedRisk.getRiskLevel());
        assertTrue(savedRisk.getRiskDescription().contains("延期"));
    }

    @Test
    @DisplayName("风险级别判断测试 - 临近截止且进度低的任务应检测为中风险")
    void testCheckTaskRiskNearDeadline() {
        Task nearDeadlineLowProgress = TestDataBuilder.aTask()
                .withId("task_004")
                .withProjectId("project_001")
                .withName("临近截止进度低的任务")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withProgress(30)
                .withDueDate(LocalDate.now().plusDays(3))
                .build();

        when(riskRepository.save(any(Risk.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(collaborationService.sendNotification(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Notification());

        riskService.checkTaskRisk(nearDeadlineLowProgress);

        ArgumentCaptor<Risk> riskCaptor = ArgumentCaptor.forClass(Risk.class);
        verify(riskRepository, times(1)).save(riskCaptor.capture());

        Risk savedRisk = riskCaptor.getValue();
        assertEquals(Constants.RISK_LEVEL_MEDIUM, savedRisk.getRiskLevel());
    }

    @Test
    @DisplayName("风险级别判断测试 - 非常临近截止且进度低的任务应检测为高风险")
    void testCheckTaskRiskVeryNearDeadline() {
        Task veryNearDeadlineLowProgress = TestDataBuilder.aTask()
                .withId("task_005")
                .withProjectId("project_001")
                .withName("非常临近截止进度低的任务")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withProgress(50)
                .withDueDate(LocalDate.now().plusDays(1))
                .build();

        when(riskRepository.save(any(Risk.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(collaborationService.sendNotification(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Notification());

        riskService.checkTaskRisk(veryNearDeadlineLowProgress);

        ArgumentCaptor<Risk> riskCaptor = ArgumentCaptor.forClass(Risk.class);
        verify(riskRepository, times(1)).save(riskCaptor.capture());

        Risk savedRisk = riskCaptor.getValue();
        assertEquals(Constants.RISK_LEVEL_HIGH, savedRisk.getRiskLevel());
    }

    @Test
    @DisplayName("风险检测测试 - 正常任务不应触发风险检测")
    void testCheckTaskRiskNormalTask() {
        riskService.checkTaskRisk(normalTask);

        verify(riskRepository, never()).save(any(Risk.class));
        verify(collaborationService, never()).sendNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("风险检测测试 - 已完成任务不应触发风险检测")
    void testCheckTaskRiskCompletedTask() {
        Task completedTask = TestDataBuilder.aTask()
                .withId("task_006")
                .withStatus(Constants.TASK_STATUS_COMPLETED)
                .withProgress(100)
                .withDueDate(LocalDate.now().minusDays(5))
                .build();

        riskService.checkTaskRisk(completedTask);

        verify(riskRepository, never()).save(any(Risk.class));
    }

    @Test
    @DisplayName("风险检测测试 - 临近截止但进度高的任务不应触发风险")
    void testCheckTaskRiskNearDeadlineHighProgress() {
        Task nearDeadlineHighProgress = TestDataBuilder.aTask()
                .withId("task_007")
                .withProjectId("project_001")
                .withName("临近截止但进度高的任务")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withProgress(85)
                .withDueDate(LocalDate.now().plusDays(2))
                .build();

        riskService.checkTaskRisk(nearDeadlineHighProgress);

        verify(riskRepository, never()).save(any(Risk.class));
    }

    @Test
    @DisplayName("风险检测测试 - 无截止日期的任务不应触发风险检测")
    void testCheckTaskRiskNoDueDate() {
        Task noDueDateTask = TestDataBuilder.aTask()
                .withId("task_008")
                .withProjectId("project_001")
                .withName("无截止日期任务")
                .withStatus(Constants.TASK_STATUS_IN_PROGRESS)
                .withProgress(20)
                .withDueDate(null)
                .build();

        riskService.checkTaskRisk(noDueDateTask);

        verify(riskRepository, never()).save(any(Risk.class));
        verify(collaborationService, never()).sendNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("风险告警通知测试 - 应发送风险告警通知")
    void testRiskAlertNotification() {
        when(riskRepository.save(any(Risk.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(collaborationService.sendNotification(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Notification notif = new Notification();
                    notif.setNotificationId("notif_001");
                    return notif;
                });

        riskService.checkTaskRisk(overdueTask);

        ArgumentCaptor<String> notifTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> notifTitleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> notifContentCaptor = ArgumentCaptor.forClass(String.class);

        verify(collaborationService, times(1)).sendNotification(
                anyString(), anyString(), anyString(),
                notifTypeCaptor.capture(),
                notifTitleCaptor.capture(),
                notifContentCaptor.capture()
        );

        assertEquals(Constants.NOTIFICATION_TYPE_RISK_ALERT, notifTypeCaptor.getValue());
        assertEquals("风险告警", notifTitleCaptor.getValue());
        assertTrue(notifContentCaptor.getValue().contains("延期"));
    }

    @Test
    @DisplayName("风险状态更新测试 - 应正确更新风险状态")
    void testUpdateRiskStatus() {
        Risk existingRisk = TestDataBuilder.aRisk()
                .withId("risk_001")
                .withStatus(Constants.RISK_STATUS_IDENTIFIED)
                .build();

        when(riskRepository.findById("risk_001")).thenReturn(Optional.of(existingRisk));

        riskService.updateRiskStatus("risk_001", Constants.RISK_STATUS_MONITORING);

        ArgumentCaptor<Risk> riskCaptor = ArgumentCaptor.forClass(Risk.class);
        verify(riskRepository, times(1)).save(riskCaptor.capture());

        assertEquals(Constants.RISK_STATUS_MONITORING, riskCaptor.getValue().getRiskStatus());
        assertNull(riskCaptor.getValue().getResolvedAt());
    }

    @Test
    @DisplayName("风险状态更新测试 - 标记为已解决应设置解决时间")
    void testUpdateRiskStatusResolved() {
        Risk existingRisk = TestDataBuilder.aRisk()
                .withId("risk_001")
                .withStatus(Constants.RISK_STATUS_IDENTIFIED)
                .build();

        when(riskRepository.findById("risk_001")).thenReturn(Optional.of(existingRisk));

        riskService.updateRiskStatus("risk_001", Constants.RISK_STATUS_RESOLVED);

        ArgumentCaptor<Risk> riskCaptor = ArgumentCaptor.forClass(Risk.class);
        verify(riskRepository, times(1)).save(riskCaptor.capture());

        assertEquals(Constants.RISK_STATUS_RESOLVED, riskCaptor.getValue().getRiskStatus());
        assertNotNull(riskCaptor.getValue().getResolvedAt());
    }

    @Test
    @DisplayName("风险查询测试 - 应返回项目的活跃风险")
    void testGetActiveRisksByProject() {
        Risk activeRisk1 = TestDataBuilder.aRisk()
                .withId("risk_001")
                .withProjectId("project_001")
                .withStatus(Constants.RISK_STATUS_IDENTIFIED)
                .build();
        Risk activeRisk2 = TestDataBuilder.aRisk()
                .withId("risk_002")
                .withProjectId("project_001")
                .withStatus(Constants.RISK_STATUS_MONITORING)
                .build();
        Risk resolvedRisk = TestDataBuilder.aRisk()
                .withId("risk_003")
                .withProjectId("project_001")
                .withStatus(Constants.RISK_STATUS_RESOLVED)
                .build();

        when(riskRepository.findByProjectIdAndRiskStatus("project_001", Constants.RISK_STATUS_IDENTIFIED))
                .thenReturn(Arrays.asList(activeRisk1));

        List<Risk> activeRisks = riskService.getActiveRisksByProject("project_001");

        assertEquals(1, activeRisks.size());
        assertEquals("risk_001", activeRisks.get(0).getRiskId());
    }

    @Test
    @DisplayName("风险查询测试 - 应返回项目的所有风险")
    void testGetRisksByProject() {
        Risk risk1 = TestDataBuilder.aRisk()
                .withId("risk_001")
                .withProjectId("project_001")
                .build();
        Risk risk2 = TestDataBuilder.aRisk()
                .withId("risk_002")
                .withProjectId("project_001")
                .build();

        when(riskRepository.findByProjectId("project_001")).thenReturn(Arrays.asList(risk1, risk2));

        List<Risk> risks = riskService.getRisksByProject("project_001");

        assertEquals(2, risks.size());
    }

    @Test
    @DisplayName("风险查询测试 - 无风险的项目应返回空列表")
    void testGetRisksByProjectEmpty() {
        when(riskRepository.findByProjectId("project_999")).thenReturn(Collections.emptyList());
        when(riskRepository.findByProjectIdAndRiskStatus("project_999", Constants.RISK_STATUS_IDENTIFIED))
                .thenReturn(Collections.emptyList());

        List<Risk> risks = riskService.getRisksByProject("project_999");
        List<Risk> activeRisks = riskService.getActiveRisksByProject("project_999");

        assertTrue(risks.isEmpty());
        assertTrue(activeRisks.isEmpty());
    }

    @Test
    @DisplayName("定时风险扫描测试 - 应批量检测多个任务的风险")
    void testBatchRiskScanning() {
        List<Task> tasksToScan = Arrays.asList(
                overdueTask,
                urgentTask,
                normalTask
        );

        when(riskRepository.save(any(Risk.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(collaborationService.sendNotification(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Notification());

        for (Task task : tasksToScan) {
            riskService.checkTaskRisk(task);
        }

        verify(riskRepository, times(2)).save(any(Risk.class));
        verify(collaborationService, times(2)).sendNotification(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("风险级别差异测试 - 延期 vs 临近截止的风险级别不同")
    void testRiskLevelDifference() {
        List<Risk> savedRisks = new java.util.ArrayList<>();

        when(riskRepository.save(any(Risk.class))).thenAnswer(invocation -> {
            Risk risk = invocation.getArgument(0);
            savedRisks.add(risk);
            return risk;
        });
        when(collaborationService.sendNotification(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Notification());

        riskService.checkTaskRisk(overdueTask);
        riskService.checkTaskRisk(urgentTask);

        assertEquals(2, savedRisks.size());

        Risk overdueRisk = savedRisks.stream()
                .filter(r -> r.getTaskId().equals("task_003"))
                .findFirst()
                .orElseThrow();
        Risk urgentRisk = savedRisks.stream()
                .filter(r -> r.getTaskId().equals("task_002"))
                .findFirst()
                .orElseThrow();

        assertEquals(Constants.RISK_LEVEL_HIGH, overdueRisk.getRiskLevel());
        assertEquals(Constants.RISK_LEVEL_MEDIUM, urgentRisk.getRiskLevel());
    }
}
