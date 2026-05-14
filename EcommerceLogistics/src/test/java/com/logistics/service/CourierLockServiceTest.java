package com.logistics.service;

import com.logistics.builder.TestDataBuilder;
import com.logistics.config.CourierLockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("配送员锁定机制测试")
class CourierLockServiceTest {

    @Mock
    private CourierLockConfig courierLockConfig;

    @InjectMocks
    private CourierLockService courierLockService;

    private static final String TEST_COURIER_ID = "courier_lock_test_001";
    private static final String TEST_LOGISTICS_ID = "logistics_lock_test_001";
    private static final String TEST_LOGISTICS_ID_2 = "logistics_lock_test_002";

    @BeforeEach
    void setUp() {
        when(courierLockConfig.getTimeoutSeconds(anyString())).thenAnswer(invocation -> {
            String urgency = invocation.getArgument(0);
            return switch (urgency) {
                case CourierLockService.URGENCY_SUPER_URGENT -> 2L;
                case CourierLockService.URGENCY_URGENT -> 5L;
                default -> 30L;
            };
        });
        when(courierLockConfig.getAllTimeouts()).thenReturn(new HashMap<>());
    }

    @Test
    @DisplayName("测试配送员分配前获取分布式锁的正确性")
    void testAcquireDistributedLockSuccess() {
        boolean locked = courierLockService.tryLock(TEST_COURIER_ID, TEST_LOGISTICS_ID, CourierLockService.URGENCY_NORMAL);
        assertTrue(locked, "应该成功获取锁");
        assertTrue(courierLockService.isLocked(TEST_COURIER_ID), "配送员应该处于锁定状态");

        CourierLockService.CourierLock lockInfo = courierLockService.getLockInfo(TEST_COURIER_ID);
        assertNotNull(lockInfo, "应该能获取锁信息");
        assertEquals(TEST_COURIER_ID, lockInfo.getCourierId());
        assertEquals(TEST_LOGISTICS_ID, lockInfo.getLogisticsId());
        assertEquals(CourierLockService.URGENCY_NORMAL, lockInfo.getUrgency());
    }

    @Test
    @DisplayName("测试并发分配时锁冲突处理")
    void testConcurrentLockConflict() throws InterruptedException {
        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            String logisticsId = "logistics_" + i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    boolean locked = courierLockService.tryLock(TEST_COURIER_ID, logisticsId, CourierLockService.URGENCY_NORMAL);
                    if (locked) {
                        successCount.incrementAndGet();
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
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "所有线程应该完成");

        assertEquals(1, successCount.get(), "应该只有一个线程成功获取锁");
        assertEquals(threadCount - 1, failCount.get(), "其他线程应该获取锁失败");
    }

    @Test
    @DisplayName("测试不同配送紧急程度下的锁定超时差异")
    void testLockTimeoutByUrgency() {
        String courierNormal = "courier_normal";
        String courierUrgent = "courier_urgent";
        String courierSuper = "courier_super";

        assertTrue(courierLockService.tryLock(courierNormal, "logistics_n", CourierLockService.URGENCY_NORMAL));
        assertTrue(courierLockService.tryLock(courierUrgent, "logistics_u", CourierLockService.URGENCY_URGENT));
        assertTrue(courierLockService.tryLock(courierSuper, "logistics_s", CourierLockService.URGENCY_SUPER_URGENT));

        CourierLockService.CourierLock normalLock = courierLockService.getLockInfo(courierNormal);
        CourierLockService.CourierLock urgentLock = courierLockService.getLockInfo(courierUrgent);
        CourierLockService.CourierLock superLock = courierLockService.getLockInfo(courierSuper);

        assertNotNull(normalLock);
        assertNotNull(urgentLock);
        assertNotNull(superLock);

        assertTrue(normalLock.getExpireTime().isAfter(urgentLock.getExpireTime()),
                "普通配送的过期时间应该晚于紧急配送");
        assertTrue(urgentLock.getExpireTime().isAfter(superLock.getExpireTime()),
                "紧急配送的过期时间应该晚于超紧急配送");
    }

    @Test
    @DisplayName("测试锁定释放与恢复的正确性")
    void testLockReleaseAndRecovery() {
        assertTrue(courierLockService.tryLock(TEST_COURIER_ID, TEST_LOGISTICS_ID, CourierLockService.URGENCY_NORMAL));
        assertTrue(courierLockService.isLocked(TEST_COURIER_ID));

        courierLockService.releaseLock(TEST_COURIER_ID, TEST_LOGISTICS_ID);
        assertFalse(courierLockService.isLocked(TEST_COURIER_ID), "释放锁后配送员应该不再被锁定");

        assertTrue(courierLockService.tryLock(TEST_COURIER_ID, TEST_LOGISTICS_ID_2, CourierLockService.URGENCY_NORMAL),
                "锁释放后应该可以重新获取");
    }

    @Test
    @DisplayName("测试错误的任务ID无法释放锁")
    void testReleaseLockWithWrongLogisticsId() {
        assertTrue(courierLockService.tryLock(TEST_COURIER_ID, TEST_LOGISTICS_ID, CourierLockService.URGENCY_NORMAL));

        courierLockService.releaseLock(TEST_COURIER_ID, TEST_LOGISTICS_ID_2);
        assertTrue(courierLockService.isLocked(TEST_COURIER_ID), "使用错误的logisticsId不应该释放锁");

        courierLockService.releaseLock(TEST_COURIER_ID, TEST_LOGISTICS_ID);
        assertFalse(courierLockService.isLocked(TEST_COURIER_ID), "使用正确的logisticsId应该释放锁");
    }

    @Test
    @DisplayName("测试带重试的锁定机制")
    void testLockWithRetry() {
        new Thread(() -> {
            try {
                courierLockService.tryLock(TEST_COURIER_ID, TEST_LOGISTICS_ID, CourierLockService.URGENCY_NORMAL);
                Thread.sleep(500);
                courierLockService.releaseLock(TEST_COURIER_ID, TEST_LOGISTICS_ID);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();

        boolean result = courierLockService.tryLockWithRetry(
                TEST_COURIER_ID, TEST_LOGISTICS_ID_2, CourierLockService.URGENCY_NORMAL, 3);
        assertTrue(result, "重试机制应该能成功获取锁");
    }

    @Test
    @DisplayName("测试null紧急程度使用默认配置")
    void testNullUrgencyUsesDefault() {
        boolean locked = courierLockService.tryLock(TEST_COURIER_ID, TEST_LOGISTICS_ID, null);
        assertTrue(locked, "null紧急程度应该使用默认配置");
        
        CourierLockService.CourierLock lockInfo = courierLockService.getLockInfo(TEST_COURIER_ID);
        assertNotNull(lockInfo);
        assertEquals(CourierLockService.URGENCY_NORMAL, lockInfo.getUrgency());
    }
}
