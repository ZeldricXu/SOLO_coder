package com.assetmanage.service;

import com.assetmanage.dto.InventoryDiffHandleRequest;
import com.assetmanage.entity.Asset;
import com.assetmanage.entity.InventoryCheck;
import com.assetmanage.entity.InventoryDifference;
import com.assetmanage.exception.BusinessException;
import com.assetmanage.repository.InventoryCheckRepository;
import com.assetmanage.repository.InventoryDifferenceRepository;
import com.assetmanage.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryCheckRepository checkRepository;

    @Mock
    private InventoryDifferenceRepository diffRepository;

    @Mock
    private AssetService assetService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private InventoryService inventoryService;

    private InventoryCheck inProgressCheck;
    private InventoryCheck completedCheck;
    private InventoryDifference locationDiff;
    private InventoryDifference statusDiff;
    private InventoryDifference quantityDiff;
    private Asset testAsset;

    @BeforeEach
    void setUp() {
        inProgressCheck = TestDataBuilder.buildInProgressInventoryCheck();
        completedCheck = TestDataBuilder.buildCompletedInventoryCheck();
        locationDiff = TestDataBuilder.buildLocationDifference();
        statusDiff = TestDataBuilder.buildStatusDifference();
        quantityDiff = TestDataBuilder.buildQuantityDifference();
        testAsset = TestDataBuilder.buildIdleAsset();
    }

    @Test
    @DisplayName("测试创建盘点任务成功")
    void testCreateInventoryCheckSuccess() {
        when(checkRepository.save(any(InventoryCheck.class))).thenAnswer(invocation -> 
                invocation.getArgument(0));

        String checkId = inventoryService.createInventoryCheck("full", "研发部", 50);

        assertNotNull(checkId, "盘点任务ID不应为空");

        verify(checkRepository).save(argThat(check -> {
            assertEquals("full", check.getCheckType());
            assertEquals("研发部", check.getCheckDepartment());
            assertEquals(50, check.getTotalAssets());
            assertEquals("in_progress", check.getCheckStatus());
            assertEquals(0, check.getCheckedAssets());
            assertEquals(0, check.getMatchedAssets());
            assertEquals(0, check.getDiffAssets());
            return true;
        }));
    }

    @Test
    @DisplayName("测试创建差异记录 - 位置差异")
    void testCreateLocationDifference() {
        when(diffRepository.save(any(InventoryDifference.class))).thenAnswer(invocation -> 
                invocation.getArgument(0));
        when(checkRepository.findById(inProgressCheck.getCheckId()))
                .thenReturn(Optional.of(inProgressCheck));
        when(checkRepository.save(any(InventoryCheck.class))).thenReturn(inProgressCheck);

        String diffId = inventoryService.createDifference(
                inProgressCheck.getCheckId(),
                testAsset.getAssetId(),
                "办公区A",
                "办公区B",
                "location_diff"
        );

        assertNotNull(diffId, "差异ID不应为空");

        verify(diffRepository).save(argThat(diff -> {
            assertEquals("location_diff", diff.getDiffType());
            assertEquals("办公区A", diff.getSystemLocation());
            assertEquals("办公区B", diff.getActualLocation());
            assertEquals("pending", diff.getDiffStatus());
            return true;
        }));

        verify(checkRepository).save(argThat(check -> {
            assertEquals(6, check.getDiffAssets(), "差异资产数应该增加1");
            return true;
        }));
    }

    @Test
    @DisplayName("测试差异检测准确性 - 位置差异处理")
    void testHandleLocationDifference() {
        InventoryDiffHandleRequest request = new InventoryDiffHandleRequest();
        request.setDiffId(locationDiff.getDiffId());
        request.setOperatorId(TestDataBuilder.TEST_OPERATOR_ID);

        when(diffRepository.findById(locationDiff.getDiffId()))
                .thenReturn(Optional.of(locationDiff));
        when(assetService.getAssetById(locationDiff.getAssetId())).thenReturn(testAsset);
        when(diffRepository.save(any(InventoryDifference.class))).thenAnswer(invocation -> 
                invocation.getArgument(0));
        when(checkRepository.findById(inProgressCheck.getCheckId()))
                .thenReturn(Optional.of(inProgressCheck));
        when(diffRepository.findByCheckIdAndDiffStatus(inProgressCheck.getCheckId(), "pending"))
                .thenReturn(Collections.emptyList());
        when(checkRepository.save(any(InventoryCheck.class))).thenReturn(inProgressCheck);

        assertDoesNotThrow(() -> inventoryService.handleDifference(request));

        verify(assetService).save(argThat(asset -> {
            assertEquals("办公区B", asset.getLocation(), "资产位置应该更新为实际位置");
            return true;
        }));

        verify(historyService).recordHistory(eq(locationDiff.getAssetId()), eq("inventory"), anyString(), eq(TestDataBuilder.TEST_OPERATOR_ID));
    }

    @Test
    @DisplayName("测试状态差异处理")
    void testHandleStatusDifference() {
        InventoryDiffHandleRequest request = new InventoryDiffHandleRequest();
        request.setDiffId(statusDiff.getDiffId());
        request.setOperatorId(TestDataBuilder.TEST_OPERATOR_ID);

        Asset statusAsset = TestDataBuilder.buildIdleAsset();
        statusAsset.setAssetId(statusDiff.getAssetId());

        when(diffRepository.findById(statusDiff.getDiffId()))
                .thenReturn(Optional.of(statusDiff));
        when(assetService.getAssetById(statusDiff.getAssetId())).thenReturn(statusAsset);
        when(diffRepository.save(any(InventoryDifference.class))).thenAnswer(invocation -> 
                invocation.getArgument(0));
        when(checkRepository.findById(inProgressCheck.getCheckId()))
                .thenReturn(Optional.of(inProgressCheck));
        when(diffRepository.findByCheckIdAndDiffStatus(inProgressCheck.getCheckId(), "pending"))
                .thenReturn(Collections.emptyList());
        when(checkRepository.save(any(InventoryCheck.class))).thenReturn(inProgressCheck);

        assertDoesNotThrow(() -> inventoryService.handleDifference(request));

        verify(historyService).recordHistory(eq(statusDiff.getAssetId()), eq("inventory"), anyString(), eq(TestDataBuilder.TEST_OPERATOR_ID));
    }

    @Test
    @DisplayName("测试数量差异处理")
    void testHandleQuantityDifference() {
        InventoryDiffHandleRequest request = new InventoryDiffHandleRequest();
        request.setDiffId(quantityDiff.getDiffId());
        request.setOperatorId(TestDataBuilder.TEST_OPERATOR_ID);

        Asset missingAsset = TestDataBuilder.buildIdleAsset();
        missingAsset.setAssetId(quantityDiff.getAssetId());

        when(diffRepository.findById(quantityDiff.getDiffId()))
                .thenReturn(Optional.of(quantityDiff));
        when(assetService.getAssetById(quantityDiff.getAssetId())).thenReturn(missingAsset);
        when(diffRepository.save(any(InventoryDifference.class))).thenAnswer(invocation -> 
                invocation.getArgument(0));
        when(checkRepository.findById(inProgressCheck.getCheckId()))
                .thenReturn(Optional.of(inProgressCheck));
        when(diffRepository.findByCheckIdAndDiffStatus(inProgressCheck.getCheckId(), "pending"))
                .thenReturn(Collections.emptyList());
        when(checkRepository.save(any(InventoryCheck.class))).thenReturn(inProgressCheck);

        assertDoesNotThrow(() -> inventoryService.handleDifference(request));

        verify(historyService).recordHistory(eq(quantityDiff.getAssetId()), eq("inventory"), anyString(), eq(TestDataBuilder.TEST_OPERATOR_ID));
    }

    @Test
    @DisplayName("测试差异处理审核机制 - 已处理差异不能重复处理")
    void testCannotHandleAlreadyHandledDifference() {
        InventoryDifference handledDiff = TestDataBuilder.buildHandledDifference();
        InventoryDiffHandleRequest request = new InventoryDiffHandleRequest();
        request.setDiffId(handledDiff.getDiffId());

        when(diffRepository.findById(handledDiff.getDiffId()))
                .thenReturn(Optional.of(handledDiff));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> inventoryService.handleDifference(request));

        assertEquals("该差异已处理", exception.getMessage());

        verify(assetService, never()).save(any());
        verify(diffRepository, never()).save(any(InventoryDifference.class));
    }

    @Test
    @DisplayName("测试盘点统计计算正确性 - 所有差异处理后状态变为completed")
    void testAllDifferencesHandledMarksCheckCompleted() {
        InventoryDiffHandleRequest request = new InventoryDiffHandleRequest();
        request.setDiffId(locationDiff.getDiffId());
        request.setOperatorId(TestDataBuilder.TEST_OPERATOR_ID);

        when(diffRepository.findById(locationDiff.getDiffId()))
                .thenReturn(Optional.of(locationDiff));
        when(assetService.getAssetById(locationDiff.getAssetId())).thenReturn(testAsset);
        when(diffRepository.save(any(InventoryDifference.class))).thenAnswer(invocation -> 
                invocation.getArgument(0));
        when(checkRepository.findById(inProgressCheck.getCheckId()))
                .thenReturn(Optional.of(inProgressCheck));
        when(diffRepository.findByCheckIdAndDiffStatus(inProgressCheck.getCheckId(), "pending"))
                .thenReturn(Collections.emptyList());
        when(checkRepository.save(any(InventoryCheck.class))).thenAnswer(invocation -> 
                invocation.getArgument(0));

        inventoryService.handleDifference(request);

        verify(checkRepository).save(argThat(check -> {
            assertEquals("completed", check.getCheckStatus(), "所有差异处理后状态应该是completed");
            assertNotNull(check.getCheckedAt(), "应该有盘点完成时间");
            assertEquals(41, check.getMatchedAssets(), "匹配资产数应该增加1");
            assertEquals(4, check.getDiffAssets(), "差异资产数应该减少1");
            return true;
        }));
    }

    @Test
    @DisplayName("测试盘点统计计算 - 部分差异处理")
    void testPartialDifferencesHandled() {
        InventoryDiffHandleRequest request = new InventoryDiffHandleRequest();
        request.setDiffId(locationDiff.getDiffId());
        request.setOperatorId(TestDataBuilder.TEST_OPERATOR_ID);

        List<InventoryDifference> remainingDiffs = Arrays.asList(statusDiff, quantityDiff);

        when(diffRepository.findById(locationDiff.getDiffId()))
                .thenReturn(Optional.of(locationDiff));
        when(assetService.getAssetById(locationDiff.getAssetId())).thenReturn(testAsset);
        when(diffRepository.save(any(InventoryDifference.class))).thenAnswer(invocation -> 
                invocation.getArgument(0));
        when(checkRepository.findById(inProgressCheck.getCheckId()))
                .thenReturn(Optional.of(inProgressCheck));
        when(diffRepository.findByCheckIdAndDiffStatus(inProgressCheck.getCheckId(), "pending"))
                .thenReturn(remainingDiffs);
        when(checkRepository.save(any(InventoryCheck.class))).thenAnswer(invocation -> 
                invocation.getArgument(0));

        inventoryService.handleDifference(request);

        verify(checkRepository).save(argThat(check -> {
            assertEquals("in_progress", check.getCheckStatus(), "仍有待处理差异时状态应该是in_progress");
            assertNull(check.getCheckedAt(), "不应该有盘点完成时间");
            return true;
        }));
    }

    @Test
    @DisplayName("测试获取盘点任务详情")
    void testGetCheckById() {
        when(checkRepository.findById(inProgressCheck.getCheckId()))
                .thenReturn(Optional.of(inProgressCheck));

        InventoryCheck result = inventoryService.getCheckById(inProgressCheck.getCheckId());

        assertNotNull(result);
        assertEquals(inProgressCheck.getCheckId(), result.getCheckId());
    }

    @Test
    @DisplayName("测试获取不存在的盘点任务抛出异常")
    void testGetNonExistentCheckThrowsException() {
        String nonExistentId = "non_existent_" + System.currentTimeMillis();
        when(checkRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> inventoryService.getCheckById(nonExistentId));

        assertEquals("盘点任务不存在: " + nonExistentId, exception.getMessage());
    }

    @Test
    @DisplayName("测试获取差异详情")
    void testGetDifferenceById() {
        when(diffRepository.findById(locationDiff.getDiffId()))
                .thenReturn(Optional.of(locationDiff));

        InventoryDifference result = inventoryService.getDifferenceById(locationDiff.getDiffId());

        assertNotNull(result);
        assertEquals(locationDiff.getDiffId(), result.getDiffId());
    }

    @Test
    @DisplayName("测试获取盘点任务的所有差异")
    void testGetDifferencesByCheck() {
        List<InventoryDifference> diffs = TestDataBuilder.buildMultipleDifferences();

        when(diffRepository.findByCheckId(inProgressCheck.getCheckId())).thenReturn(diffs);

        List<InventoryDifference> result = inventoryService.getDifferencesByCheck(inProgressCheck.getCheckId());

        assertEquals(3, result.size());
    }

    @Test
    @DisplayName("测试获取待处理差异列表")
    void testGetPendingDifferences() {
        List<InventoryDifference> pendingDiffs = Arrays.asList(locationDiff, statusDiff);

        when(diffRepository.findByDiffStatus("pending")).thenReturn(pendingDiffs);

        List<InventoryDifference> result = inventoryService.getPendingDifferences();

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("测试获取所有盘点任务")
    void testGetAllChecks() {
        List<InventoryCheck> checks = Arrays.asList(inProgressCheck, completedCheck);

        when(checkRepository.findAllByOrderByCreatedAtDesc()).thenReturn(checks);

        List<InventoryCheck> result = inventoryService.getAllChecks();

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("测试按状态获取盘点任务")
    void testGetChecksByStatus() {
        List<InventoryCheck> inProgressChecks = Collections.singletonList(inProgressCheck);

        when(checkRepository.findByCheckStatus("in_progress")).thenReturn(inProgressChecks);

        List<InventoryCheck> result = inventoryService.getChecksByStatus("in_progress");

        assertEquals(1, result.size());
        assertEquals("in_progress", result.get(0).getCheckStatus());
    }

    @Test
    @DisplayName("测试手动完成盘点任务")
    void testCompleteCheck() {
        when(checkRepository.findById(inProgressCheck.getCheckId()))
                .thenReturn(Optional.of(inProgressCheck));
        when(checkRepository.save(any(InventoryCheck.class))).thenAnswer(invocation -> 
                invocation.getArgument(0));

        assertDoesNotThrow(() -> inventoryService.completeCheck(inProgressCheck.getCheckId()));

        verify(checkRepository).save(argThat(check -> {
            assertEquals("completed", check.getCheckStatus());
            assertNotNull(check.getCheckedAt());
            return true;
        }));
    }

    @Test
    @DisplayName("测试位置差异处理后资产位置更新正确")
    void testAssetLocationUpdatedAfterLocationDiffHandled() {
        InventoryDiffHandleRequest request = new InventoryDiffHandleRequest();
        request.setDiffId(locationDiff.getDiffId());
        request.setOperatorId(TestDataBuilder.TEST_OPERATOR_ID);

        assertEquals("办公区A", testAsset.getLocation(), "初始位置应该是办公区A");

        when(diffRepository.findById(locationDiff.getDiffId()))
                .thenReturn(Optional.of(locationDiff));
        when(assetService.getAssetById(locationDiff.getAssetId())).thenReturn(testAsset);
        when(diffRepository.save(any(InventoryDifference.class))).thenAnswer(invocation -> 
                invocation.getArgument(0));
        when(checkRepository.findById(inProgressCheck.getCheckId()))
                .thenReturn(Optional.of(inProgressCheck));
        when(diffRepository.findByCheckIdAndDiffStatus(inProgressCheck.getCheckId(), "pending"))
                .thenReturn(Collections.emptyList());
        when(checkRepository.save(any(InventoryCheck.class))).thenReturn(inProgressCheck);

        inventoryService.handleDifference(request);

        verify(assetService).save(argThat(asset -> {
            assertEquals("办公区B", asset.getLocation());
            return true;
        }));
    }

    @Test
    @DisplayName("测试差异处理记录完整")
    void testDifferenceHandleRecordComplete() {
        InventoryDiffHandleRequest request = new InventoryDiffHandleRequest();
        request.setDiffId(locationDiff.getDiffId());
        request.setOperatorId(TestDataBuilder.TEST_OPERATOR_ID);

        when(diffRepository.findById(locationDiff.getDiffId()))
                .thenReturn(Optional.of(locationDiff));
        when(assetService.getAssetById(locationDiff.getAssetId())).thenReturn(testAsset);
        when(diffRepository.save(any(InventoryDifference.class))).thenAnswer(invocation -> 
                invocation.getArgument(0));
        when(checkRepository.findById(inProgressCheck.getCheckId()))
                .thenReturn(Optional.of(inProgressCheck));
        when(diffRepository.findByCheckIdAndDiffStatus(inProgressCheck.getCheckId(), "pending"))
                .thenReturn(Collections.emptyList());
        when(checkRepository.save(any(InventoryCheck.class))).thenReturn(inProgressCheck);

        inventoryService.handleDifference(request);

        verify(diffRepository).save(argThat(diff -> {
            assertEquals("handled", diff.getDiffStatus());
            assertNotNull(diff.getHandledAt());
            return true;
        }));
    }
}
