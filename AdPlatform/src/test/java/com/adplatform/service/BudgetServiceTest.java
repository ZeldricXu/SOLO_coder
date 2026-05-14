package com.adplatform.service;

import com.adplatform.entity.AdBudget;
import com.adplatform.entity.AdConsume;
import com.adplatform.exception.BusinessException;
import com.adplatform.lock.DistributedLockService;
import com.adplatform.repository.AdBudgetRepository;
import com.adplatform.repository.AdConsumeRepository;
import com.adplatform.repository.AdHistoryRepository;
import com.adplatform.repository.AdInfoRepository;
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
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("预算模块单元测试 - 锁机制与并发控制")
class BudgetServiceTest {

    @Mock
    private AdBudgetRepository adBudgetRepository;

    @Mock
    private AdConsumeRepository adConsumeRepository;

    @Mock
    private AdInfoRepository adInfoRepository;

    @Mock
    private AdHistoryRepository adHistoryRepository;

    @Mock
    private DistributedLockService distributedLockService;

    @Mock
    private StatusService statusService;

    @InjectMocks
    private BudgetService budgetService;

    private AdBudget testBudget;
    private String testAdId;
    private BigDecimal totalBudget;

    @BeforeEach
    void setUp() {
        testAdId = "ad_budget_test_" + System.currentTimeMillis();
        totalBudget = new BigDecimal("1000.00");
        testBudget = TestDataBuilder.buildAdBudget(testAdId, totalBudget);
    }

    @Test
    @DisplayName("测试预算创建 - 成功场景")
    void testCreateBudget_Success() {
        when(adInfoRepository.existsById(anyString())).thenReturn(true);
        when(adBudgetRepository.save(any(AdBudget.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adHistoryRepository.save(any())).thenReturn(null);

        BigDecimal budgetAmount = new BigDecimal("5000");
        AdBudget result = budgetService.createBudget(testAdId, "daily", budgetAmount, null);

        assertNotNull(result);
        assertEquals(budgetAmount, result.getBudgetAmount());
        assertEquals(budgetAmount, result.getBudgetRemaining());
        assertEquals(BigDecimal.ZERO, result.getBudgetConsumed());
        assertEquals(budgetAmount.multiply(new BigDecimal("0.1")), result.getBudgetThreshold());
        
        ArgumentCaptor<AdBudget> budgetCaptor = ArgumentCaptor.forClass(AdBudget.class);
        verify(adBudgetRepository, times(1)).save(budgetCaptor.capture());
        AdBudget saved = budgetCaptor.getValue();
        assertEquals(testAdId, saved.getAdId());
        assertEquals("daily", saved.getBudgetType());
    }

    @Test
    @DisplayName("测试预算创建 - 广告不存在")
    void testCreateBudget_AdNotFound() {
        when(adInfoRepository.existsById(anyString())).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, 
                () -> budgetService.createBudget("non_existent_ad", "daily", new BigDecimal("1000"), null));

        assertEquals(404, exception.getCode());
        assertEquals("广告不存在", exception.getMessage());
        verify(adBudgetRepository, never()).save(any());
    }

    @Test
    @DisplayName("测试预算创建 - 金额为负")
    void testCreateBudget_NegativeAmount() {
        when(adInfoRepository.existsById(anyString())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, 
                () -> budgetService.createBudget(testAdId, "daily", new BigDecimal("-100"), null));

        assertEquals(400, exception.getCode());
        assertEquals("预算金额必须大于0", exception.getMessage());
    }

    @Test
    @DisplayName("测试预算创建 - 金额为零")
    void testCreateBudget_ZeroAmount() {
        when(adInfoRepository.existsById(anyString())).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, 
                () -> budgetService.createBudget(testAdId, "daily", BigDecimal.ZERO, null));

        assertEquals(400, exception.getCode());
        assertEquals("预算金额必须大于0", exception.getMessage());
    }

    @Test
    @DisplayName("测试预算扣减 - 成功场景")
    void testConsumeBudget_Success() {
        BigDecimal consumeAmount = new BigDecimal("50");
        
        when(distributedLockService.executeWithLock(anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<Boolean> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
        when(adBudgetRepository.findActiveBudgetByAdId(anyString())).thenReturn(Optional.of(testBudget));
        when(adBudgetRepository.save(any(AdBudget.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adConsumeRepository.save(any(AdConsume.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adHistoryRepository.save(any())).thenReturn(null);

        boolean result = budgetService.consumeBudget(testAdId, "click", consumeAmount);

        assertTrue(result);
        
        ArgumentCaptor<AdBudget> budgetCaptor = ArgumentCaptor.forClass(AdBudget.class);
        verify(adBudgetRepository, times(1)).save(budgetCaptor.capture());
        AdBudget updated = budgetCaptor.getValue();
        
        assertEquals(totalBudget.subtract(consumeAmount), updated.getBudgetRemaining());
        assertEquals(consumeAmount, updated.getBudgetConsumed());
    }

    @Test
    @DisplayName("测试预算扣减 - 预算不足")
    void testConsumeBudget_InsufficientBudget() {
        BigDecimal consumeAmount = new BigDecimal("2000");
        
        when(distributedLockService.executeWithLock(anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<Boolean> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
        when(adBudgetRepository.findActiveBudgetByAdId(anyString())).thenReturn(Optional.of(testBudget));
        when(statusService.isAdRunning(anyString())).thenReturn(true);
        when(statusService.updateStatus(anyString(), anyString(), anyString())).thenReturn(null);
        when(adHistoryRepository.save(any())).thenReturn(null);

        boolean result = budgetService.consumeBudget(testAdId, "click", consumeAmount);

        assertFalse(result);
        verify(adBudgetRepository, never()).save(any(AdBudget.class));
        verify(statusService, times(1)).updateStatus(testAdId, "ended", "预算耗尽");
    }

    @Test
    @DisplayName("测试预算扣减 - 无可用预算")
    void testConsumeBudget_NoActiveBudget() {
        when(distributedLockService.executeWithLock(anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<Boolean> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
        when(adBudgetRepository.findActiveBudgetByAdId(anyString())).thenReturn(Optional.empty());

        boolean result = budgetService.consumeBudget(testAdId, "click", new BigDecimal("50"));

        assertFalse(result);
        verify(adBudgetRepository, never()).save(any(AdBudget.class));
    }

    @Test
    @DisplayName("测试预算扣减锁机制 - 验证锁获取")
    void testConsumeBudget_LockAcquisition() {
        BigDecimal consumeAmount = new BigDecimal("50");
        
        when(distributedLockService.executeWithLock(anyString(), anyLong(), anyLong(), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    String lockKey = invocation.getArgument(0);
                    assertTrue(lockKey.startsWith("budget:lock:"));
                    assertTrue(lockKey.contains(testAdId));
                    Supplier<Boolean> supplier = invocation.getArgument(3);
                    return supplier.get();
                });
        when(adBudgetRepository.findActiveBudgetByAdId(anyString())).thenReturn(Optional.of(testBudget));
        when(adBudgetRepository.save(any(AdBudget.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adConsumeRepository.save(any(AdConsume.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adHistoryRepository.save(any())).thenReturn(null);

        boolean result = budgetService.consumeBudget(testAdId, "click", consumeAmount);

        assertTrue(result);
        verify(distributedLockService, times(1))
                .executeWithLock(eq("budget:lock:" + testAdId), any(Supplier.class));
    }

    @Test
    @DisplayName("测试预算扣减锁机制 - 锁冲突时的处理")
    void testConsumeBudget_LockConflict() {
        BigDecimal consumeAmount = new BigDecimal("50");
        
        when(distributedLockService.executeWithLock(anyString(), any(Supplier.class)))
                .thenThrow(new RuntimeException("获取分布式锁失败，请稍后重试"));

        RuntimeException exception = assertThrows(RuntimeException.class, 
                () -> budgetService.consumeBudget(testAdId, "click", consumeAmount));

        assertTrue(exception.getMessage().contains("获取分布式锁失败"));
        verify(adBudgetRepository, never()).save(any(AdBudget.class));
    }

    @Test
    @DisplayName("测试并发预算扣减 - 验证不出现超扣")
    void testConsumeBudget_Concurrent_NoOverConsumption() throws Exception {
        int threadCount = 20;
        BigDecimal perConsume = new BigDecimal("10");
        BigDecimal initialBudget = new BigDecimal("100");
        
        AdBudget concurrentBudget = TestDataBuilder.buildAdBudget("ad_concurrent_test", initialBudget);
        AtomicReference<BigDecimal> currentRemaining = new AtomicReference<>(initialBudget);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        when(distributedLockService.executeWithLock(anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<Boolean> supplier = invocation.getArgument(1);
                    synchronized (BudgetServiceTest.class) {
                        return supplier.get();
                    }
                });
        when(adBudgetRepository.findActiveBudgetByAdId(anyString()))
                .thenAnswer(invocation -> Optional.of(
                        TestDataBuilder.buildAdBudget("ad_concurrent_test", initialBudget, 
                                initialBudget.subtract(currentRemaining.get()))
                ));
        when(adBudgetRepository.save(any(AdBudget.class)))
                .thenAnswer(invocation -> {
                    AdBudget budget = invocation.getArgument(0);
                    currentRemaining.set(budget.getBudgetRemaining());
                    return budget;
                });
        when(adConsumeRepository.save(any(AdConsume.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adHistoryRepository.save(any())).thenReturn(null);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    boolean result = budgetService.consumeBudget("ad_concurrent_test", "click", perConsume);
                    if (result) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        BigDecimal expectedSuccesses = initialBudget.divide(perConsume, 0, BigDecimal.ROUND_DOWN);
        int maxSuccess = expectedSuccesses.intValue();
        
        assertTrue(successCount.get() <= maxSuccess, 
                "成功次数不应该超过最大可能值: " + successCount.get() + " > " + maxSuccess);
        assertTrue(currentRemaining.get().compareTo(BigDecimal.ZERO) >= 0, 
                "预算剩余不应该为负数: " + currentRemaining.get());
        
        BigDecimal totalConsumed = initialBudget.subtract(currentRemaining.get());
        BigDecimal expectedTotalConsumed = perConsume.multiply(new BigDecimal(successCount.get()));
        assertEquals(expectedTotalConsumed, totalConsumed, "总消耗金额计算错误");
    }

    @Test
    @DisplayName("测试预算扣减 - 锁释放验证")
    void testConsumeBudget_LockRelease() {
        BigDecimal consumeAmount = new BigDecimal("50");
        AtomicInteger lockAcquireCount = new AtomicInteger(0);
        AtomicInteger lockReleaseCount = new AtomicInteger(0);

        when(distributedLockService.executeWithLock(anyString(), anyLong(), anyLong(), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    lockAcquireCount.incrementAndGet();
                    try {
                        Supplier<Boolean> supplier = invocation.getArgument(3);
                        return supplier.get();
                    } finally {
                        lockReleaseCount.incrementAndGet();
                    }
                });
        when(adBudgetRepository.findActiveBudgetByAdId(anyString())).thenReturn(Optional.of(testBudget));
        when(adBudgetRepository.save(any(AdBudget.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adConsumeRepository.save(any(AdConsume.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adHistoryRepository.save(any())).thenReturn(null);

        budgetService.consumeBudget(testAdId, "click", consumeAmount);

        assertEquals(1, lockAcquireCount.get(), "锁应该被获取一次");
        assertEquals(1, lockReleaseCount.get(), "锁应该被释放一次");
    }

    @Test
    @DisplayName("测试预算扣减 - 消耗记录创建")
    void testConsumeBudget_ConsumeRecord() {
        BigDecimal consumeAmount = new BigDecimal("50");
        
        when(distributedLockService.executeWithLock(anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<Boolean> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
        when(adBudgetRepository.findActiveBudgetByAdId(anyString())).thenReturn(Optional.of(testBudget));
        when(adBudgetRepository.save(any(AdBudget.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adConsumeRepository.save(any(AdConsume.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adHistoryRepository.save(any())).thenReturn(null);

        budgetService.consumeBudget(testAdId, "click", consumeAmount);

        ArgumentCaptor<AdConsume> consumeCaptor = ArgumentCaptor.forClass(AdConsume.class);
        verify(adConsumeRepository, times(1)).save(consumeCaptor.capture());
        AdConsume saved = consumeCaptor.getValue();
        
        assertEquals(testAdId, saved.getAdId());
        assertEquals("click", saved.getConsumeType());
        assertEquals(consumeAmount, saved.getConsumeAmount());
        assertNotNull(saved.getConsumeTime());
    }

    @Test
    @DisplayName("测试预算扣减 - 预算耗尽后停止投放")
    void testConsumeBudget_ExhaustedStopPlacement() {
        BigDecimal consumeAmount = new BigDecimal("1000");
        AdBudget exhaustedBudget = TestDataBuilder.buildAdBudget(testAdId, totalBudget, 
                totalBudget.subtract(new BigDecimal("999")));
        
        when(distributedLockService.executeWithLock(anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<Boolean> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
        when(adBudgetRepository.findActiveBudgetByAdId(anyString())).thenReturn(Optional.of(exhaustedBudget));
        when(adBudgetRepository.save(any(AdBudget.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adConsumeRepository.save(any(AdConsume.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(statusService.isAdRunning(anyString())).thenReturn(true);
        when(statusService.updateStatus(anyString(), anyString(), anyString())).thenReturn(null);
        when(adHistoryRepository.save(any())).thenReturn(null);

        boolean result = budgetService.consumeBudget(testAdId, "click", consumeAmount);

        assertTrue(result);
        verify(statusService, times(1)).updateStatus(testAdId, "ended", "预算耗尽");
    }

    @Test
    @DisplayName("测试预算扣减 - 阈值预警")
    void testConsumeBudget_ThresholdWarning() {
        BigDecimal threshold = new BigDecimal("100");
        AdBudget budget = AdBudget.builder()
                .budgetId("budget_test")
                .adId(testAdId)
                .budgetType("daily")
                .budgetAmount(new BigDecimal("1000"))
                .budgetConsumed(new BigDecimal("850"))
                .budgetRemaining(new BigDecimal("150"))
                .budgetThreshold(threshold)
                .createdAt(java.time.LocalDateTime.now())
                .updatedAt(java.time.LocalDateTime.now())
                .build();
        
        BigDecimal consumeAmount = new BigDecimal("60");

        when(distributedLockService.executeWithLock(anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<Boolean> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
        when(adBudgetRepository.findActiveBudgetByAdId(anyString())).thenReturn(Optional.of(budget));
        when(adBudgetRepository.save(any(AdBudget.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adConsumeRepository.save(any(AdConsume.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adHistoryRepository.save(any())).thenReturn(null);

        boolean result = budgetService.consumeBudget(testAdId, "click", consumeAmount);

        assertTrue(result);
        ArgumentCaptor<Object> historyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(adHistoryRepository, atLeastOnce()).save(historyCaptor.capture());
    }

    @Test
    @DisplayName("测试获取预算余额")
    void testGetBudgetRemaining() {
        when(adBudgetRepository.findActiveBudgetByAdId(anyString())).thenReturn(Optional.of(testBudget));

        BigDecimal remaining = budgetService.getBudgetRemaining(testAdId);

        assertEquals(totalBudget, remaining);
    }

    @Test
    @DisplayName("测试获取预算余额 - 无预算时返回零")
    void testGetBudgetRemaining_NoBudget() {
        when(adBudgetRepository.findActiveBudgetByAdId(anyString())).thenReturn(Optional.empty());

        BigDecimal remaining = budgetService.getBudgetRemaining(testAdId);

        assertEquals(BigDecimal.ZERO, remaining);
    }

    @Test
    @DisplayName("测试检查预算是否充足")
    void testHasEnoughBudget() {
        when(adBudgetRepository.findActiveBudgetByAdId(anyString())).thenReturn(Optional.of(testBudget));

        assertTrue(budgetService.hasEnoughBudget(testAdId, new BigDecimal("500")));
        assertTrue(budgetService.hasEnoughBudget(testAdId, new BigDecimal("1000")));
        assertFalse(budgetService.hasEnoughBudget(testAdId, new BigDecimal("1001")));
    }
}
