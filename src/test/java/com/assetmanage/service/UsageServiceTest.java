package com.assetmanage.service;

import com.assetmanage.common.AssetLockManager;
import com.assetmanage.dto.AssetUseRequest;
import com.assetmanage.dto.AssetReturnRequest;
import com.assetmanage.dto.UseResponse;
import com.assetmanage.entity.Asset;
import com.assetmanage.entity.UsageRecord;
import com.assetmanage.enums.AssetStatus;
import com.assetmanage.exception.BusinessException;
import com.assetmanage.repository.UsageRecordRepository;
import com.assetmanage.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsageServiceTest {

    @Mock
    private UsageRecordRepository usageRecordRepository;

    @Mock
    private AssetService assetService;

    @Mock
    private HistoryService historyService;

    @Mock
    private MaintenanceService maintenanceService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private AssetLockManager lockManager;

    @InjectMocks
    private UsageService usageService;

    private Asset idleAsset;
    private Asset inUseAsset;
    private Asset maintenanceAsset;
    private Asset scrappedAsset;
    private AssetUseRequest validRequest;

    @BeforeEach
    void setUp() {
        idleAsset = TestDataBuilder.buildIdleAsset();
        inUseAsset = TestDataBuilder.buildInUseAsset();
        maintenanceAsset = TestDataBuilder.buildMaintenanceAsset();
        scrappedAsset = TestDataBuilder.buildScrappedAsset();

        validRequest = new TestDataBuilder.AssetUseRequestBuilder().build();
    }

    @Test
    @DisplayName("测试闲置资产领用成功")
    void testUseIdleAssetSuccess() {
        when(lockManager.tryLock(eq(validRequest.getAssetId()), eq(validRequest.getUserId()), 
                eq(30L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(assetService.getAssetById(validRequest.getAssetId())).thenReturn(idleAsset);
        
        UsageRecord mockRecord = TestDataBuilder.buildActiveUsageRecord();
        when(usageRecordRepository.save(any(UsageRecord.class))).thenReturn(mockRecord);
        when(lockManager.releaseLock(validRequest.getAssetId(), validRequest.getUserId())).thenReturn(true);

        UseResponse response = usageService.useAsset(validRequest);

        assertNotNull(response, "领用响应不应为空");
        assertNotNull(response.getUsageId(), "领用记录ID不应为空");
        assertEquals("active", response.getStatus(), "领用状态应为active");

        verify(assetService).save(argThat(asset -> 
                AssetStatus.IN_USE.getCode().equals(asset.getAssetStatus())
        ));
        verify(maintenanceService).adjustMaintenancePlan(validRequest.getAssetId());
        verify(historyService).recordHistory(eq(validRequest.getAssetId()), eq("use"), anyString(), any());
        verify(analysisService).updateStatistics();
    }

    @Test
    @DisplayName("测试领用失败：资产已被锁定")
    void testUseAssetLockedByAnotherUser() {
        when(lockManager.tryLock(eq(validRequest.getAssetId()), eq(validRequest.getUserId()), 
                eq(30L), eq(TimeUnit.SECONDS))).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, 
                () -> usageService.useAsset(validRequest));

        assertEquals("领用失败：资产正被其他用户锁定，请稍后重试", exception.getMessage());
        
        verify(assetService, never()).getAssetById(anyString());
        verify(usageRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("测试领用失败：资产已被领用")
    void testUseAssetAlreadyInUse() {
        when(lockManager.tryLock(eq(validRequest.getAssetId()), eq(validRequest.getUserId()), 
                eq(30L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(assetService.getAssetById(validRequest.getAssetId())).thenReturn(inUseAsset);
        when(lockManager.releaseLock(validRequest.getAssetId(), validRequest.getUserId())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, 
                () -> usageService.useAsset(validRequest));

        assertEquals("领用失败：资产已被领用", exception.getMessage());
    }

    @Test
    @DisplayName("测试领用失败：资产正在维护中")
    void testUseAssetUnderMaintenance() {
        when(lockManager.tryLock(eq(validRequest.getAssetId()), eq(validRequest.getUserId()), 
                eq(30L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(assetService.getAssetById(validRequest.getAssetId())).thenReturn(maintenanceAsset);
        when(lockManager.releaseLock(validRequest.getAssetId(), validRequest.getUserId())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, 
                () -> usageService.useAsset(validRequest));

        assertEquals("领用失败：资产正在维护中", exception.getMessage());
    }

    @Test
    @DisplayName("测试领用失败：资产已报废")
    void testUseAssetAlreadyScrapped() {
        when(lockManager.tryLock(eq(validRequest.getAssetId()), eq(validRequest.getUserId()), 
                eq(30L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(assetService.getAssetById(validRequest.getAssetId())).thenReturn(scrappedAsset);
        when(lockManager.releaseLock(validRequest.getAssetId(), validRequest.getUserId())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, 
                () -> usageService.useAsset(validRequest));

        assertEquals("领用失败：资产已报废", exception.getMessage());
    }

    @Test
    @DisplayName("测试资产归还成功")
    void testReturnAssetSuccess() {
        AssetReturnRequest returnRequest = new AssetReturnRequest();
        returnRequest.setAssetId(idleAsset.getAssetId());
        returnRequest.setOperatorId(TestDataBuilder.TEST_OPERATOR_ID);

        UsageRecord activeRecord = TestDataBuilder.buildActiveUsageRecord();
        when(usageRecordRepository.findActiveByAssetId(returnRequest.getAssetId()))
                .thenReturn(Optional.of(activeRecord));
        when(assetService.getAssetById(returnRequest.getAssetId())).thenReturn(inUseAsset);

        assertDoesNotThrow(() -> usageService.returnAsset(returnRequest));

        verify(usageRecordRepository).save(argThat(record -> 
                "returned".equals(record.getUsageStatus()) && record.getActualReturn() != null
        ));
        verify(assetService).save(argThat(asset -> 
                AssetStatus.IDLE.getCode().equals(asset.getAssetStatus()) && 
                asset.getCurrentUserId() == null
        ));
    }

    @Test
    @DisplayName("测试归还失败：没有活跃领用记录")
    void testReturnAssetNoActiveUsage() {
        AssetReturnRequest returnRequest = new AssetReturnRequest();
        returnRequest.setAssetId(idleAsset.getAssetId());

        when(usageRecordRepository.findActiveByAssetId(returnRequest.getAssetId()))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class, 
                () -> usageService.returnAsset(returnRequest));

        assertEquals("该资产没有活跃的领用记录", exception.getMessage());
    }

    @Test
    @DisplayName("测试领用后资产状态变更")
    void testAssetStatusChangeAfterUsage() {
        when(lockManager.tryLock(eq(validRequest.getAssetId()), eq(validRequest.getUserId()), 
                eq(30L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(assetService.getAssetById(validRequest.getAssetId())).thenReturn(idleAsset);
        
        UsageRecord mockRecord = TestDataBuilder.buildActiveUsageRecord();
        when(usageRecordRepository.save(any(UsageRecord.class))).thenReturn(mockRecord);
        when(lockManager.releaseLock(validRequest.getAssetId(), validRequest.getUserId())).thenReturn(true);

        usageService.useAsset(validRequest);

        verify(assetService).save(argThat(asset -> {
            assertEquals(AssetStatus.IN_USE.getCode(), asset.getAssetStatus(),
                    "资产状态应该变为使用中");
            assertEquals(validRequest.getUserId(), asset.getCurrentUserId(),
                    "当前使用人应该是领用人");
            return true;
        }));
    }

    @Test
    @DisplayName("测试并发领用场景 - 只有第一个成功")
    void testConcurrentUsageOnlyFirstSucceeds() throws InterruptedException {
        String assetId = idleAsset.getAssetId();
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicReference<String> successUserId = new AtomicReference<>();

        AssetLockManager realLockManager = new AssetLockManager();

        for (int i = 0; i < threadCount; i++) {
            String userId = "user_" + i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    if (realLockManager.tryLock(assetId, userId, 30, TimeUnit.SECONDS)) {
                        int current = successCount.incrementAndGet();
                        if (current == 1) {
                            successUserId.set(userId);
                        }
                        realLockManager.releaseLock(assetId, userId);
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        endLatch.await(5, TimeUnit.SECONDS);

        assertEquals(1, successCount.get(), "应该只有一个用户成功领用");
        assertEquals(threadCount - 1, failCount.get(), "其他用户应该领用失败");
        assertNotNull(successUserId.get(), "应该有一个成功的用户ID");
    }

    @Test
    @DisplayName("测试锁定释放后可再次领用")
    void testLockReleaseAllowsReuse() {
        String assetId = idleAsset.getAssetId();
        String user1 = "user_1";
        String user2 = "user_2";

        AssetLockManager realLockManager = new AssetLockManager();

        assertTrue(realLockManager.tryLock(assetId, user1), "用户1应该能获取锁");
        assertTrue(realLockManager.isLocked(assetId), "资产应该被锁定");

        assertFalse(realLockManager.tryLock(assetId, user2), "用户2在锁未释放时不能获取");

        assertTrue(realLockManager.releaseLock(assetId, user1), "锁应该被释放");

        assertTrue(realLockManager.tryLock(assetId, user2), "用户2在锁释放后应该能获取");
        assertEquals(user2, realLockManager.getLockOwner(assetId), "所有者应该是用户2");
    }

    @Test
    @DisplayName("测试领用过程中发生异常时释放锁")
    void testLockReleasedOnException() {
        when(lockManager.tryLock(eq(validRequest.getAssetId()), eq(validRequest.getUserId()), 
                eq(30L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(assetService.getAssetById(validRequest.getAssetId())).thenThrow(
                new RuntimeException("模拟数据库异常"));
        when(lockManager.releaseLock(validRequest.getAssetId(), validRequest.getUserId())).thenReturn(true);

        assertThrows(RuntimeException.class, () -> usageService.useAsset(validRequest));

        verify(lockManager).releaseLock(validRequest.getAssetId(), validRequest.getUserId());
    }

    @Test
    @DisplayName("测试获取活跃领用记录")
    void testGetActiveUsageByAsset() {
        String assetId = idleAsset.getAssetId();
        UsageRecord activeRecord = TestDataBuilder.buildActiveUsageRecord();

        when(usageRecordRepository.findActiveByAssetId(assetId)).thenReturn(Optional.of(activeRecord));

        Optional<UsageRecord> result = usageService.getActiveUsageByAsset(assetId);

        assertTrue(result.isPresent(), "应该找到活跃领用记录");
        assertEquals(activeRecord.getUsageId(), result.get().getUsageId());
    }

    @Test
    @DisplayName("测试获取资产使用记录列表")
    void testGetUsageRecordsByAsset() {
        String assetId = idleAsset.getAssetId();
        java.util.List<UsageRecord> records = java.util.Arrays.asList(
                TestDataBuilder.buildActiveUsageRecord(),
                TestDataBuilder.buildReturnedUsageRecord()
        );

        when(usageRecordRepository.findByAssetIdOrderByUsageStartDesc(assetId)).thenReturn(records);

        java.util.List<UsageRecord> result = usageService.getUsageRecordsByAsset(assetId);

        assertEquals(2, result.size(), "应该返回2条记录");
    }
}
