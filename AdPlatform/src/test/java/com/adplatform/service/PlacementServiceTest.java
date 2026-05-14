package com.adplatform.service;

import com.adplatform.dto.PlacementRequest;
import com.adplatform.dto.PlacementResponse;
import com.adplatform.entity.AdInfo;
import com.adplatform.entity.AdPlacement;
import com.adplatform.exception.BusinessException;
import com.adplatform.queue.EffectEventQueue;
import com.adplatform.repository.AdHistoryRepository;
import com.adplatform.repository.AdInfoRepository;
import com.adplatform.repository.AdPlacementRepository;
import com.adplatform.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("投放模块单元测试")
class PlacementServiceTest {

    @Mock
    private AdPlacementRepository adPlacementRepository;

    @Mock
    private AdInfoRepository adInfoRepository;

    @Mock
    private AdHistoryRepository adHistoryRepository;

    @Mock
    private StatusService statusService;

    @Mock
    private BudgetService budgetService;

    @Mock
    private TargetingService targetingService;

    @Mock
    private EffectEventQueue effectEventQueue;

    @InjectMocks
    private PlacementService placementService;

    private AdInfo approvedAd;
    private AdInfo pendingAd;
    private AdInfo runningAd;
    private String testAdId;

    @BeforeEach
    void setUp() {
        testAdId = "ad_test_" + System.currentTimeMillis();
        approvedAd = TestDataBuilder.buildApprovedAd();
        pendingAd = TestDataBuilder.buildPendingAd();
        runningAd = TestDataBuilder.buildRunningAd();
    }

    @Test
    @DisplayName("测试投放配置创建 - 成功场景")
    void testCreatePlacement_Success() {
        when(adInfoRepository.findByAdId(anyString())).thenReturn(Optional.of(approvedAd));
        when(adPlacementRepository.save(any(AdPlacement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(statusService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedAd);
        when(adHistoryRepository.save(any())).thenReturn(null);

        PlacementRequest request = TestDataBuilder.buildPlacementRequest(approvedAd.getAdId());
        PlacementResponse response = placementService.createPlacement(request);

        assertNotNull(response);
        assertNotNull(response.getPlacementId());
        assertTrue(response.getPlacementId().startsWith("placement_"));
        assertEquals("active", response.getStatus());

        ArgumentCaptor<AdPlacement> placementCaptor = ArgumentCaptor.forClass(AdPlacement.class);
        verify(adPlacementRepository, times(1)).save(placementCaptor.capture());
        AdPlacement savedPlacement = placementCaptor.getValue();
        assertEquals(approvedAd.getAdId(), savedPlacement.getAdId());
        assertEquals("active", savedPlacement.getPlacementStatus());
        assertNotNull(savedPlacement.getPlacementStart());
        assertNotNull(savedPlacement.getPlacementEnd());
    }

    @Test
    @DisplayName("测试投放配置创建 - 广告不存在")
    void testCreatePlacement_AdNotFound() {
        when(adInfoRepository.findByAdId(anyString())).thenReturn(Optional.empty());

        PlacementRequest request = TestDataBuilder.buildPlacementRequest("non_existent_ad");
        BusinessException exception = assertThrows(BusinessException.class, 
                () -> placementService.createPlacement(request));

        assertEquals(404, exception.getCode());
        assertEquals("广告不存在", exception.getMessage());
        verify(adPlacementRepository, never()).save(any());
        verify(statusService, never()).updateStatus(any(), any(), any());
    }

    @Test
    @DisplayName("测试投放配置创建 - 广告状态不允许投放")
    void testCreatePlacement_InvalidStatus() {
        when(adInfoRepository.findByAdId(anyString())).thenReturn(Optional.of(pendingAd));

        PlacementRequest request = TestDataBuilder.buildPlacementRequest(pendingAd.getAdId());
        BusinessException exception = assertThrows(BusinessException.class, 
                () -> placementService.createPlacement(request));

        assertEquals(400, exception.getCode());
        assertTrue(exception.getMessage().contains("广告状态不允许投放"));
        verify(adPlacementRepository, never()).save(any());
    }

    @Test
    @DisplayName("测试投放启动 - 成功场景")
    void testStartPlacement_Success() {
        when(adInfoRepository.findByAdId(anyString())).thenReturn(Optional.of(approvedAd));
        when(statusService.isAdRunnable(anyString())).thenReturn(true);
        when(budgetService.hasEnoughBudget(anyString(), any(BigDecimal.class))).thenReturn(true);
        when(adPlacementRepository.findByAdId(anyString())).thenReturn(Collections.singletonList(
                TestDataBuilder.buildAdPlacement(approvedAd.getAdId(), "inactive")
        ));
        when(statusService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedAd);
        when(adHistoryRepository.save(any())).thenReturn(null);

        boolean result = placementService.startPlacement(approvedAd.getAdId());

        assertTrue(result);
        verify(adPlacementRepository, times(1)).findByAdId(approvedAd.getAdId());
        verify(statusService, times(1)).updateStatus(approvedAd.getAdId(), "running", "启动投放");
    }

    @Test
    @DisplayName("测试投放启动 - 预算不足")
    void testStartPlacement_InsufficientBudget() {
        when(adInfoRepository.findByAdId(anyString())).thenReturn(Optional.of(approvedAd));
        when(statusService.isAdRunnable(anyString())).thenReturn(true);
        when(budgetService.hasEnoughBudget(anyString(), any(BigDecimal.class))).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, 
                () -> placementService.startPlacement(approvedAd.getAdId()));

        assertEquals(400, exception.getCode());
        assertEquals("广告预算不足", exception.getMessage());
        verify(statusService, never()).updateStatus(any(), any(), any());
    }

    @Test
    @DisplayName("测试投放停止 - 成功场景")
    void testStopPlacement_Success() {
        when(adInfoRepository.findByAdId(anyString())).thenReturn(Optional.of(runningAd));
        when(statusService.isAdRunning(anyString())).thenReturn(true);
        when(adPlacementRepository.findByAdId(anyString())).thenReturn(Collections.singletonList(
                TestDataBuilder.buildAdPlacement(runningAd.getAdId(), "active")
        ));
        when(statusService.updateStatus(anyString(), anyString(), anyString())).thenReturn(runningAd);
        when(adHistoryRepository.save(any())).thenReturn(null);

        boolean result = placementService.stopPlacement(runningAd.getAdId());

        assertTrue(result);
        verify(statusService, times(1)).updateStatus(runningAd.getAdId(), "paused", "暂停投放");
    }

    @Test
    @DisplayName("测试投放停止 - 广告未在投放中")
    void testStopPlacement_NotRunning() {
        when(adInfoRepository.findByAdId(anyString())).thenReturn(Optional.of(approvedAd));
        when(statusService.isAdRunning(anyString())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, 
                () -> placementService.stopPlacement(approvedAd.getAdId()));

        assertEquals(400, exception.getCode());
        assertEquals("广告未在投放中", exception.getMessage());
    }

    @Test
    @DisplayName("测试投放异步化 - 验证立即返回响应")
    void testPlacementAsync_ImmediateResponse() throws Exception {
        when(adInfoRepository.findByAdId(anyString())).thenReturn(Optional.of(approvedAd));
        when(adPlacementRepository.save(any(AdPlacement.class))).thenAnswer(invocation -> {
            Thread.sleep(100);
            return invocation.getArgument(0);
        });
        when(statusService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedAd);
        when(adHistoryRepository.save(any())).thenReturn(null);

        long startTime = System.currentTimeMillis();
        PlacementRequest request = TestDataBuilder.buildPlacementRequest(approvedAd.getAdId());
        PlacementResponse response = placementService.createPlacement(request);
        long elapsedTime = System.currentTimeMillis() - startTime;

        assertNotNull(response);
        assertTrue(elapsedTime < 500, "响应时间应该小于500ms");
    }

    @Test
    @DisplayName("测试投放并发 - 验证投放配置的线程安全")
    void testPlacementConcurrent_ThreadSafety() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        when(adInfoRepository.findByAdId(anyString())).thenReturn(Optional.of(approvedAd));
        when(adPlacementRepository.save(any(AdPlacement.class))).thenAnswer(invocation -> {
            Thread.sleep(50);
            return invocation.getArgument(0);
        });
        when(statusService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedAd);
        when(adHistoryRepository.save(any())).thenReturn(null);

        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    PlacementRequest request = TestDataBuilder.buildPlacementRequest(
                            "ad_concurrent_" + threadNum
                    );
                    placementService.createPlacement(request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(threadCount, successCount.get(), "所有投放请求应该成功");
        assertEquals(0, failCount.get(), "不应该有失败的请求");
        verify(adPlacementRepository, times(threadCount)).save(any(AdPlacement.class));
    }

    @Test
    @DisplayName("测试投放配置创建 - 带定向条件")
    void testCreatePlacement_WithTargeting() {
        when(adInfoRepository.findByAdId(anyString())).thenReturn(Optional.of(approvedAd));
        when(adPlacementRepository.save(any(AdPlacement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(targetingService.createTargeting(anyString(), anyString(), anyMap())).thenReturn(
                TestDataBuilder.buildAdTarget(approvedAd.getAdId())
        );
        when(statusService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedAd);
        when(adHistoryRepository.save(any())).thenReturn(null);

        PlacementRequest request = TestDataBuilder.buildPlacementRequestWithTargeting(approvedAd.getAdId());
        PlacementResponse response = placementService.createPlacement(request);

        assertNotNull(response);
        verify(targetingService, times(1)).createTargeting(
                eq(approvedAd.getAdId()),
                eq("demographic"),
                anyMap()
        );
    }

    @Test
    @DisplayName("测试投放配置创建 - 带预算配置")
    void testCreatePlacement_WithBudget() {
        when(adInfoRepository.findByAdId(anyString())).thenReturn(Optional.of(approvedAd));
        when(adPlacementRepository.save(any(AdPlacement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(budgetService.createBudget(anyString(), anyString(), any(BigDecimal.class), any()))
                .thenReturn(TestDataBuilder.buildAdBudget(approvedAd.getAdId(), new BigDecimal("1000")));
        when(statusService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedAd);
        when(adHistoryRepository.save(any())).thenReturn(null);

        PlacementRequest request = TestDataBuilder.buildPlacementRequest(
                approvedAd.getAdId(), 
                new BigDecimal("1000")
        );
        PlacementResponse response = placementService.createPlacement(request);

        assertNotNull(response);
        verify(budgetService, times(1)).createBudget(
                eq(approvedAd.getAdId()),
                eq("daily"),
                eq(new BigDecimal("1000")),
                isNull()
        );
    }

    @Test
    @DisplayName("测试投放配置创建 - 验证投放时间设置")
    void testCreatePlacement_PlacementTime() {
        LocalDateTime customStart = LocalDateTime.now().plusDays(1);
        LocalDateTime customEnd = LocalDateTime.now().plusDays(60);

        when(adInfoRepository.findByAdId(anyString())).thenReturn(Optional.of(approvedAd));
        when(adPlacementRepository.save(any(AdPlacement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(statusService.updateStatus(anyString(), anyString(), anyString())).thenReturn(approvedAd);
        when(adHistoryRepository.save(any())).thenReturn(null);

        PlacementRequest request = PlacementRequest.builder()
                .adId(approvedAd.getAdId())
                .placementChannel("mobile_app")
                .placementPosition("home_banner")
                .placementStart(customStart)
                .placementEnd(customEnd)
                .budgetAmount(new BigDecimal("1000"))
                .budgetType("total")
                .build();

        placementService.createPlacement(request);

        ArgumentCaptor<AdPlacement> placementCaptor = ArgumentCaptor.forClass(AdPlacement.class);
        verify(adPlacementRepository).save(placementCaptor.capture());
        AdPlacement saved = placementCaptor.getValue();

        assertEquals(customStart, saved.getPlacementStart());
        assertEquals(customEnd, saved.getPlacementEnd());
    }

    @Test
    @DisplayName("测试获取投放配置 - 按广告ID查询")
    void testGetPlacementsByAdId() {
        AdPlacement placement1 = TestDataBuilder.buildAdPlacement(approvedAd.getAdId());
        AdPlacement placement2 = TestDataBuilder.buildAdPlacement(approvedAd.getAdId());
        when(adPlacementRepository.findByAdId(anyString())).thenReturn(
                java.util.Arrays.asList(placement1, placement2)
        );

        java.util.List<AdPlacement> placements = placementService.getPlacementsByAdId(approvedAd.getAdId());

        assertNotNull(placements);
        assertEquals(2, placements.size());
        verify(adPlacementRepository, times(1)).findByAdId(approvedAd.getAdId());
    }

    @Test
    @DisplayName("测试获取投放配置 - 按投放ID查询")
    void testGetPlacementById() {
        AdPlacement placement = TestDataBuilder.buildAdPlacement(approvedAd.getAdId());
        when(adPlacementRepository.findByPlacementId(anyString())).thenReturn(Optional.of(placement));

        Optional<AdPlacement> result = placementService.getPlacementById(placement.getPlacementId());

        assertTrue(result.isPresent());
        assertEquals(placement.getPlacementId(), result.get().getPlacementId());
    }
}
