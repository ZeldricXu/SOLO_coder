package com.assetinventory.service;

import com.assetinventory.builder.TestDataBuilder;
import com.assetinventory.entity.Asset;
import com.assetinventory.entity.InventoryDifference;
import com.assetinventory.exception.InventoryException;
import com.assetinventory.repository.InventoryDifferenceRepository;
import com.assetinventory.util.DifferenceAlertManager;
import com.assetinventory.util.DifferenceAlertManager.AlertRecord;
import com.assetinventory.util.DifferenceAlertManager.SeverityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("差异模块单元测试 - 差异提醒机制")
class DifferenceServiceTest {

    @Mock
    private InventoryDifferenceRepository differenceRepository;

    @Mock
    private AssetService assetService;

    @Mock
    private StatisticsService statisticsService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private DifferenceService differenceService;

    private DifferenceAlertManager alertManager;

    private InventoryDifference testDiff;
    private Asset testAsset;

    @BeforeEach
    void setUp() {
        alertManager = new DifferenceAlertManager();
        testAsset = TestDataBuilder.assetBuilder()
                .assetQuantity(100)
                .assetLocation("A栋1楼")
                .buildUncountedAsset();
        testDiff = TestDataBuilder.differenceBuilder()
                .assetId(testAsset.getAssetId())
                .diffSystem(100)
                .diffActual(95)
                .diffValue(5)
                .buildQuantityDiff(100, 95);
    }

    @Test
    @DisplayName("测试差异严重程度判定 - 严重差异（差异率>=50%）")
    void testDetermineSeverity_Critical() {
        InventoryDifference criticalDiff = TestDataBuilder.differenceBuilder()
                .diffSystem(100)
                .diffActual(40)
                .diffValue(60)
                .build();

        SeverityLevel severity = alertManager.determineSeverity(criticalDiff);

        assertEquals(SeverityLevel.CRITICAL, severity);
        assertEquals(1, severity.getLevel());
        assertEquals(60000L, severity.getAlertIntervalMs());
    }

    @Test
    @DisplayName("测试差异严重程度判定 - 高优先级差异（差异率>=20%）")
    void testDetermineSeverity_High() {
        InventoryDifference highDiff = TestDataBuilder.differenceBuilder()
                .diffSystem(100)
                .diffActual(70)
                .diffValue(30)
                .build();

        SeverityLevel severity = alertManager.determineSeverity(highDiff);

        assertEquals(SeverityLevel.HIGH, severity);
        assertEquals(2, severity.getLevel());
        assertEquals(300000L, severity.getAlertIntervalMs());
    }

    @Test
    @DisplayName("测试差异严重程度判定 - 中等差异（差异率>=10%）")
    void testDetermineSeverity_Medium() {
        InventoryDifference mediumDiff = TestDataBuilder.differenceBuilder()
                .diffSystem(100)
                .diffActual(85)
                .diffValue(15)
                .build();

        SeverityLevel severity = alertManager.determineSeverity(mediumDiff);

        assertEquals(SeverityLevel.MEDIUM, severity);
        assertEquals(3, severity.getLevel());
        assertEquals(600000L, severity.getAlertIntervalMs());
    }

    @Test
    @DisplayName("测试差异严重程度判定 - 轻微差异（差异率<10%）")
    void testDetermineSeverity_Low() {
        InventoryDifference lowDiff = TestDataBuilder.differenceBuilder()
                .diffSystem(100)
                .diffActual(95)
                .diffValue(5)
                .build();

        SeverityLevel severity = alertManager.determineSeverity(lowDiff);

        assertEquals(SeverityLevel.LOW, severity);
        assertEquals(4, severity.getLevel());
        assertEquals(1800000L, severity.getAlertIntervalMs());
    }

    @Test
    @DisplayName("测试差异提醒触发 - 首次触发")
    void testTriggerAlert_FirstTime() {
        InventoryDifference diff = TestDataBuilder.differenceBuilder().buildCriticalDiff();

        assertTrue(alertManager.shouldTriggerAlert(diff));

        AlertRecord alert = alertManager.triggerAlert(diff);

        assertNotNull(alert);
        assertEquals(diff.getDiffId(), alert.getDiffId());
        assertEquals(SeverityLevel.CRITICAL, alert.getSeverity());
        assertEquals(1, alert.getAlertCount());
        assertEquals(1, alertManager.getSentAlertCount());
    }

    @Test
    @DisplayName("测试严重差异高频提醒 - 间隔内不重复提醒")
    void testCriticalAlert_HighFrequency_NoRepeatWithinInterval() {
        InventoryDifference criticalDiff = TestDataBuilder.differenceBuilder().buildCriticalDiff();

        AlertRecord firstAlert = alertManager.triggerAlert(criticalDiff);
        assertNotNull(firstAlert);

        assertFalse(alertManager.shouldTriggerAlert(criticalDiff));

        AlertRecord secondAlert = alertManager.triggerAlert(criticalDiff);
        assertNull(secondAlert);

        assertEquals(1, alertManager.getSentAlertCount());
    }

    @Test
    @DisplayName("测试不同严重程度的提醒频率差异")
    void testAlertFrequency_DifferentSeverityLevels() {
        InventoryDifference criticalDiff = TestDataBuilder.differenceBuilder().buildCriticalDiff();
        InventoryDifference highDiff = TestDataBuilder.differenceBuilder().buildHighDiff();
        InventoryDifference mediumDiff = TestDataBuilder.differenceBuilder().buildMediumDiff();
        InventoryDifference lowDiff = TestDataBuilder.differenceBuilder().buildLowDiff();

        alertManager.triggerAlert(criticalDiff);
        alertManager.triggerAlert(highDiff);
        alertManager.triggerAlert(mediumDiff);
        alertManager.triggerAlert(lowDiff);

        List<AlertRecord> criticalAlerts = alertManager.getSentAlertsBySeverity(SeverityLevel.CRITICAL);
        List<AlertRecord> highAlerts = alertManager.getSentAlertsBySeverity(SeverityLevel.HIGH);
        List<AlertRecord> mediumAlerts = alertManager.getSentAlertsBySeverity(SeverityLevel.MEDIUM);
        List<AlertRecord> lowAlerts = alertManager.getSentAlertsBySeverity(SeverityLevel.LOW);

        assertEquals(1, criticalAlerts.size());
        assertEquals(1, highAlerts.size());
        assertEquals(1, mediumAlerts.size());
        assertEquals(1, lowAlerts.size());
        assertEquals(4, alertManager.getSentAlertCount());

        assertTrue(criticalAlerts.get(0).getSeverity().getAlertIntervalMs() <
                   lowAlerts.get(0).getSeverity().getAlertIntervalMs());
    }

    @Test
    @DisplayName("测试提醒消息格式正确性")
    void testAlertMessage_FormatCorrectness() {
        InventoryDifference diff = TestDataBuilder.differenceBuilder()
                .assetId("asset_001")
                .diffType("quantity")
                .diffSystem(100)
                .diffActual(40)
                .diffValue(60)
                .buildCriticalDiff();

        AlertRecord alert = alertManager.triggerAlert(diff);

        assertNotNull(alert);
        assertNotNull(alert.getMessage());
        assertTrue(alert.getMessage().contains("资产ID: asset_001"));
        assertTrue(alert.getMessage().contains("差异类型: quantity"));
        assertTrue(alert.getMessage().contains("系统数量: 100"));
        assertTrue(alert.getMessage().contains("实际数量: 40"));
        assertTrue(alert.getMessage().contains("差异值: 60"));
        assertTrue(alert.getMessage().contains("严重差异"));
    }

    @Test
    @DisplayName("测试提醒发送机制 - 多次触发计数")
    void testAlertSending_MultipleTriggers_Count() throws InterruptedException {
        InventoryDifference diff = TestDataBuilder.differenceBuilder()
                .diffSystem(100)
                .diffActual(95)
                .diffValue(5)
                .buildLowDiff();

        for (int i = 0; i < 5; i++) {
            alertManager.triggerAlert(diff);
        }

        assertEquals(1, alertManager.getSentAlertCount());
        assertEquals(1, alertManager.getActiveAlertCount());
    }

    @Test
    @DisplayName("测试差异处理状态流转 - 待处理 -> 已确认")
    void testDifferenceStatusTransition_PendingToConfirmed() {
        InventoryDifference pendingDiff = TestDataBuilder.differenceBuilder()
                .diffId("diff_001")
                .diffType("quantity")
                .diffSystem(100)
                .diffActual(95)
                .diffValue(5)
                .buildPendingDiff();

        when(differenceRepository.findByDiffId("diff_001")).thenReturn(Optional.of(pendingDiff));
        when(assetService.getAssetByIdOrThrow(anyString())).thenReturn(testAsset);
        when(differenceRepository.save(any(InventoryDifference.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordDifferenceHistory(anyString(), anyString(), anyString());

        InventoryDifference confirmed = differenceService.processDifference("diff_001", "confirmed");

        assertEquals("confirmed", confirmed.getDiffStatus());
        verify(assetService, times(1)).updateAssetQuantity(testAsset.getAssetId(), 95);
        verify(assetService, times(1)).updateAssetStatus(testAsset.getAssetId(), "adjusted");
        verify(statisticsService, times(1)).incrementProcessedDiffCount();
    }

    @Test
    @DisplayName("测试差异处理状态流转 - 待处理 -> 已拒绝")
    void testDifferenceStatusTransition_PendingToRejected() {
        InventoryDifference pendingDiff = TestDataBuilder.differenceBuilder()
                .diffId("diff_002")
                .buildPendingDiff();

        when(differenceRepository.findByDiffId("diff_002")).thenReturn(Optional.of(pendingDiff));
        when(differenceRepository.save(any(InventoryDifference.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordDifferenceHistory(anyString(), anyString(), anyString());

        InventoryDifference rejected = differenceService.processDifference("diff_002", "rejected");

        assertEquals("rejected", rejected.getDiffStatus());
        verify(assetService, never()).updateAssetQuantity(anyString(), anyInt());
        verify(statisticsService, never()).incrementProcessedDiffCount();
    }

    @Test
    @DisplayName("测试差异处理状态流转 - 完整生命周期")
    void testDifferenceStatusTransition_FullLifecycle() {
        String diffId = "diff_lifecycle_001";
        InventoryDifference diff = TestDataBuilder.differenceBuilder()
                .diffId(diffId)
                .diffType("quantity")
                .diffSystem(200)
                .diffActual(180)
                .diffValue(20)
                .buildPendingDiff();

        when(differenceRepository.findByDiffId(diffId)).thenReturn(Optional.of(diff));
        when(assetService.getAssetByIdOrThrow(anyString())).thenReturn(testAsset);
        when(differenceRepository.save(any(InventoryDifference.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordDifferenceHistory(anyString(), anyString(), anyString());

        assertEquals("pending", diff.getDiffStatus());

        InventoryDifference afterProcess = differenceService.processDifference(diffId, "confirmed");

        assertEquals("confirmed", afterProcess.getDiffStatus());
    }

    @Test
    @DisplayName("测试已处理差异重复处理 - 拒绝")
    void testProcessDifference_AlreadyProcessed_Rejected() {
        InventoryDifference confirmedDiff = TestDataBuilder.differenceBuilder()
                .diffId("diff_003")
                .buildConfirmedDiff();

        when(differenceRepository.findByDiffId("diff_003")).thenReturn(Optional.of(confirmedDiff));

        InventoryException exception = assertThrows(InventoryException.class,
                () -> differenceService.processDifference("diff_003", "confirmed"));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("已处理"));
        verify(differenceRepository, never()).save(any(InventoryDifference.class));
    }

    @Test
    @DisplayName("测试无效的差异状态值")
    void testProcessDifference_InvalidStatus_Rejected() {
        InventoryDifference pendingDiff = TestDataBuilder.differenceBuilder()
                .diffId("diff_004")
                .buildPendingDiff();

        when(differenceRepository.findByDiffId("diff_004")).thenReturn(Optional.of(pendingDiff));

        InventoryException exception = assertThrows(InventoryException.class,
                () -> differenceService.processDifference("diff_004", "invalid_status"));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("无效"));
    }

    @Test
    @DisplayName("测试差异不存在 - 抛出异常")
    void testGetDifferenceByIdOrThrow_NotFound() {
        when(differenceRepository.findByDiffId(anyString())).thenReturn(Optional.empty());

        InventoryException exception = assertThrows(InventoryException.class,
                () -> differenceService.getDifferenceByIdOrThrow("nonexistent_diff"));

        assertEquals(404, exception.getCode());
        assertTrue(exception.getMessage().contains("差异记录不存在"));
    }

    @Test
    @DisplayName("测试位置差异处理 - 不更新数量")
    void testProcessDifference_LocationDiff_NoQuantityUpdate() {
        InventoryDifference locationDiff = TestDataBuilder.differenceBuilder()
                .diffId("diff_location_001")
                .diffType("location")
                .diffSystem(100)
                .diffActual(100)
                .diffValue(0)
                .buildLocationDiff();

        when(differenceRepository.findByDiffId("diff_location_001")).thenReturn(Optional.of(locationDiff));
        when(assetService.getAssetByIdOrThrow(anyString())).thenReturn(testAsset);
        when(differenceRepository.save(any(InventoryDifference.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(historyService).recordDifferenceHistory(anyString(), anyString(), anyString());

        InventoryDifference processed = differenceService.processDifference("diff_location_001", "confirmed");

        assertEquals("confirmed", processed.getDiffStatus());
        verify(assetService, never()).updateAssetQuantity(anyString(), anyInt());
    }

    @Test
    @DisplayName("测试创建差异记录 - 数量差异")
    void testCreateDifference_QuantityDiff() {
        when(differenceRepository.save(any(InventoryDifference.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InventoryDifference created = differenceService.createDifference(
                "count_001",
                "asset_001",
                "quantity",
                100,
                95
        );

        assertNotNull(created);
        assertEquals("quantity", created.getDiffType());
        assertEquals(100, created.getDiffSystem());
        assertEquals(95, created.getDiffActual());
        assertEquals(5, created.getDiffValue());
        assertEquals("pending", created.getDiffStatus());
        verify(differenceRepository, times(1)).save(any(InventoryDifference.class));
    }

    @Test
    @DisplayName("测试创建差异记录 - 位置差异")
    void testCreateDifference_LocationDiff() {
        when(differenceRepository.save(any(InventoryDifference.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InventoryDifference created = differenceService.createDifference(
                "count_002",
                "asset_002",
                "location",
                100,
                100
        );

        assertNotNull(created);
        assertEquals("location", created.getDiffType());
        assertEquals(0, created.getDiffValue());
        assertEquals("pending", created.getDiffStatus());
    }

    @Test
    @DisplayName("测试按状态获取差异")
    void testGetDifferencesByStatus() {
        List<InventoryDifference> pendingDiffs = TestDataBuilder.differenceBuilder()
                .buildMultipleBySeverity(2, 1, 1, 1);
        List<InventoryDifference> confirmedDiffs = TestDataBuilder.differenceBuilder()
                .buildMultipleBySeverity(1, 0, 0, 0);
        confirmedDiffs.forEach(d -> d.setDiffStatus("confirmed"));

        when(differenceRepository.findByDiffStatus("pending")).thenReturn(pendingDiffs);
        when(differenceRepository.findByDiffStatus("confirmed")).thenReturn(confirmedDiffs);

        assertEquals(5, differenceService.getDifferencesByStatus("pending").size());
        assertEquals(1, differenceService.getDifferencesByStatus("confirmed").size());
    }

    @Test
    @DisplayName("测试按资产ID获取差异")
    void testGetDifferencesByAssetId() {
        String assetId = "asset_with_diffs";
        List<InventoryDifference> assetDiffs = TestDataBuilder.differenceBuilder()
                .assetId(assetId)
                .buildMultipleBySeverity(1, 1, 0, 0);

        when(differenceRepository.findByAssetId(assetId)).thenReturn(assetDiffs);

        List<InventoryDifference> result = differenceService.getDifferencesByAssetId(assetId);

        assertEquals(2, result.size());
        result.forEach(diff -> assertEquals(assetId, diff.getAssetId()));
    }

    @Test
    @DisplayName("测试按盘点记录ID获取差异")
    void testGetDifferencesByCountId() {
        String countId = "count_with_diffs";
        List<InventoryDifference> countDiffs = TestDataBuilder.differenceBuilder()
                .countId(countId)
                .buildMultipleBySeverity(0, 0, 1, 2);

        when(differenceRepository.findByCountId(countId)).thenReturn(countDiffs);

        List<InventoryDifference> result = differenceService.getDifferencesByCountId(countId);

        assertEquals(3, result.size());
        result.forEach(diff -> assertEquals(countId, diff.getCountId()));
    }

    @Test
    @DisplayName("测试提醒管理器重置功能")
    void testAlertManager_Reset() {
        InventoryDifference diff1 = TestDataBuilder.differenceBuilder().buildCriticalDiff();
        InventoryDifference diff2 = TestDataBuilder.differenceBuilder().buildHighDiff();

        alertManager.triggerAlert(diff1);
        alertManager.triggerAlert(diff2);

        assertEquals(2, alertManager.getSentAlertCount());
        assertEquals(2, alertManager.getActiveAlertCount());

        alertManager.reset();

        assertEquals(0, alertManager.getSentAlertCount());
        assertEquals(0, alertManager.getActiveAlertCount());
    }

    @Test
    @DisplayName("测试清理单个差异提醒")
    void testClearAlert_SingleDiff() {
        InventoryDifference diff = TestDataBuilder.differenceBuilder()
                .diffId("diff_to_clear")
                .buildCriticalDiff();

        alertManager.triggerAlert(diff);
        assertEquals(1, alertManager.getActiveAlertCount());

        alertManager.clearAlert("diff_to_clear");
        assertEquals(0, alertManager.getActiveAlertCount());
    }

    @Test
    @DisplayName("测试清理所有差异提醒")
    void testClearAllAlerts() {
        for (int i = 0; i < 10; i++) {
            InventoryDifference diff = TestDataBuilder.differenceBuilder()
                    .diffId("diff_" + i)
                    .buildLowDiff();
            alertManager.triggerAlert(diff);
        }

        assertEquals(10, alertManager.getActiveAlertCount());

        alertManager.clearAllAlerts();
        assertEquals(0, alertManager.getActiveAlertCount());
    }

    @Test
    @DisplayName("测试差异严重程度边界值")
    void testSeverityLevel_BoundaryValues() {
        InventoryDifference justBelowCritical = TestDataBuilder.differenceBuilder()
                .diffSystem(100)
                .diffActual(51)
                .diffValue(49)
                .build();
        InventoryDifference exactlyHigh = TestDataBuilder.differenceBuilder()
                .diffSystem(100)
                .diffActual(80)
                .diffValue(20)
                .build();
        InventoryDifference exactlyMedium = TestDataBuilder.differenceBuilder()
                .diffSystem(100)
                .diffActual(90)
                .diffValue(10)
                .build();

        assertEquals(SeverityLevel.HIGH, alertManager.determineSeverity(justBelowCritical));
        assertEquals(SeverityLevel.HIGH, alertManager.determineSeverity(exactlyHigh));
        assertEquals(SeverityLevel.MEDIUM, alertManager.determineSeverity(exactlyMedium));
    }

    @Test
    @DisplayName("测试系统数量为0时的严重程度判定")
    void testDetermineSeverity_SystemQuantityZero() {
        InventoryDifference zeroSystemDiff = TestDataBuilder.differenceBuilder()
                .diffSystem(0)
                .diffActual(10)
                .diffValue(10)
                .build();

        SeverityLevel severity = alertManager.determineSeverity(zeroSystemDiff);

        assertEquals(SeverityLevel.CRITICAL, severity);
    }

    @Test
    @DisplayName("测试获取所有已发送提醒")
    void testGetSentAlerts_AllRecords() {
        InventoryDifference diff1 = TestDataBuilder.differenceBuilder()
                .diffId("diff_1")
                .buildCriticalDiff();
        InventoryDifference diff2 = TestDataBuilder.differenceBuilder()
                .diffId("diff_2")
                .buildHighDiff();
        InventoryDifference diff3 = TestDataBuilder.differenceBuilder()
                .diffId("diff_3")
                .buildLowDiff();

        alertManager.triggerAlert(diff1);
        alertManager.triggerAlert(diff2);
        alertManager.triggerAlert(diff3);

        List<AlertRecord> allAlerts = alertManager.getSentAlerts();

        assertEquals(3, allAlerts.size());
    }
}
