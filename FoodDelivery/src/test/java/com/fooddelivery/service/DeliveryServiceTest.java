package com.fooddelivery.service;

import com.fooddelivery.builder.TestDataBuilder;
import com.fooddelivery.entity.Delivery;
import com.fooddelivery.entity.Order;
import com.fooddelivery.entity.Rider;
import com.fooddelivery.exception.BusinessException;
import com.fooddelivery.repository.DeliveryRepository;
import com.fooddelivery.util.RiderLockManager;
import com.fooddelivery.util.RiderLockManager.LockType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("配送模块测试 - 骑手锁定机制")
class DeliveryServiceTest {

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private RiderService riderService;

    @Mock
    private OrderService orderService;

    @Mock
    private StatusService statusService;

    @Mock
    private AnalysisService analysisService;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private DeliveryService deliveryService;

    private RiderLockManager riderLockManager;

    private Order testOrder;
    private Rider testRider;

    @BeforeEach
    void setUp() {
        riderLockManager = new RiderLockManager();
        testOrder = TestDataBuilder.buildOrder();
        testRider = TestDataBuilder.buildRider();
    }

    @Test
    @DisplayName("骑手分配前获取分布式锁 - 成功")
    void testAcquireLockBeforeAllocation() {
        String riderId = testRider.getRiderId();
        String orderId = testOrder.getOrderId();

        RiderLockManager.RiderLock lock = riderLockManager.tryLock(riderId, orderId, LockType.NORMAL_ORDER);

        assertNotNull(lock, "应该成功获取锁");
        assertEquals(riderId, lock.getRiderId());
        assertEquals(orderId, lock.getOrderId());
        assertEquals(LockType.NORMAL_ORDER, lock.getLockType());
        assertTrue(riderLockManager.isLocked(riderId));
    }

    @Test
    @DisplayName("骑手分配前获取分布式锁 - 已被占用时返回null")
    void testAcquireLockWhenAlreadyLocked() {
        String riderId = testRider.getRiderId();
        String orderId1 = testOrder.getOrderId();
        String orderId2 = "order_002";

        RiderLockManager.RiderLock lock1 = riderLockManager.tryLock(riderId, orderId1, LockType.NORMAL_ORDER);
        assertNotNull(lock1);

        RiderLockManager.RiderLock lock2 = riderLockManager.tryLock(riderId, orderId2, LockType.NORMAL_ORDER);
        assertNull(lock2, "锁已被占用，应该返回null");
    }

    @Test
    @DisplayName("并发分配时锁冲突处理")
    void testConcurrentLockConflict() throws InterruptedException {
        int threadCount = 10;
        String riderId = testRider.getRiderId();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    String orderId = "order_concurrent_" + index;
                    RiderLockManager.RiderLock lock = riderLockManager.tryLock(
                            riderId, orderId, LockType.NORMAL_ORDER);
                    if (lock != null) {
                        successCount.incrementAndGet();
                        Thread.sleep(50);
                        riderLockManager.releaseLock(riderId, orderId);
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threadCount, successCount.get() + failCount.get());
        assertTrue(successCount.get() >= 1, "至少应该有一个线程成功获取锁");
    }

    @Test
    @DisplayName("紧急订单锁定超时更短 - 3秒")
    void testUrgencyOrderLockTimeout() {
        assertEquals(3, LockType.URGENCY_ORDER.getTimeout());
        assertEquals(java.util.concurrent.TimeUnit.SECONDS, LockType.URGENCY_ORDER.getUnit());
    }

    @Test
    @DisplayName("普通订单锁定超时更长 - 10秒")
    void testNormalOrderLockTimeout() {
        assertEquals(10, LockType.NORMAL_ORDER.getTimeout());
        assertEquals(java.util.concurrent.TimeUnit.SECONDS, LockType.NORMAL_ORDER.getUnit());
    }

    @Test
    @DisplayName("锁定释放 - 释放后可重新获取")
    void testLockReleaseAndReacquire() {
        String riderId = testRider.getRiderId();
        String orderId1 = "order_001";
        String orderId2 = "order_002";

        RiderLockManager.RiderLock lock1 = riderLockManager.tryLock(riderId, orderId1, LockType.NORMAL_ORDER);
        assertNotNull(lock1);
        assertTrue(riderLockManager.isLocked(riderId));

        boolean released = riderLockManager.releaseLock(riderId, orderId1);
        assertTrue(released, "应该成功释放锁");
        assertFalse(riderLockManager.isLocked(riderId));

        RiderLockManager.RiderLock lock2 = riderLockManager.tryLock(riderId, orderId2, LockType.NORMAL_ORDER);
        assertNotNull(lock2, "释放后应该可以重新获取锁");
        assertEquals(orderId2, lock2.getOrderId());
    }

    @Test
    @DisplayName("锁定恢复 - 超时后自动释放")
    void testLockRecoveryAfterTimeout() {
        RiderLockManager testManager = new RiderLockManager() {
            @Override
            public RiderLock tryLock(String riderId, String orderId, LockType lockType) {
                RiderLock lock = super.tryLock(riderId, orderId, lockType);
                if (lock != null) {
                    try {
                        java.lang.reflect.Field expireField = RiderLock.class.getDeclaredField("expireTime");
                        expireField.setAccessible(true);
                        expireField.set(lock, System.currentTimeMillis() - 1000);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                return lock;
            }
        };

        String riderId = testRider.getRiderId();
        String orderId = testOrder.getOrderId();
        testManager.tryLock(riderId, orderId, LockType.NORMAL_ORDER);

        assertFalse(testManager.isLocked(riderId), "超时后锁应该自动释放");

        RiderLockManager.RiderLock newLock = testManager.tryLock(riderId, "other_order", LockType.NORMAL_ORDER);
        assertNotNull(newLock, "超时后应该可以被其他订单获取");
    }

    @Test
    @DisplayName("骑手不可用时的拒绝处理")
    void testRejectWhenRiderUnavailable() {
        Rider busyRider = TestDataBuilder.buildRiderUnavailable();
        when(riderService.selectBestRider(anyString())).thenReturn(Optional.of(busyRider));
        when(deliveryRepository.save(any(Delivery.class))).thenReturn(TestDataBuilder.buildDelivery());

        RiderLockManager realLockManager = new RiderLockManager();
        DeliveryService testService = new DeliveryService();

        try {
            java.lang.reflect.Field repoField = DeliveryService.class.getDeclaredField("deliveryRepository");
            repoField.setAccessible(true);
            repoField.set(testService, deliveryRepository);

            java.lang.reflect.Field riderField = DeliveryService.class.getDeclaredField("riderService");
            riderField.setAccessible(true);
            riderField.set(testService, riderService);

            java.lang.reflect.Field lockField = DeliveryService.class.getDeclaredField("riderLockManager");
            lockField.setAccessible(true);
            lockField.set(testService, realLockManager);

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> testService.createDelivery(testOrder, LockType.NORMAL_ORDER));

            assertEquals(400, exception.getCode());
            assertEquals("骑手不可用", exception.getMessage());

            assertFalse(realLockManager.isLocked(busyRider.getRiderId()),
                    "异常时应该释放锁");
        } catch (Exception e) {
            e.printStackTrace();
            fail("反射异常: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("不同订单紧急程度锁定超时差异 - 紧急订单短超时")
    void testLockTimeoutDifference_UrgencyOrder() {
        String riderId = testRider.getRiderId();
        String orderId = "order_urgent_001";

        RiderLockManager.RiderLock lock = riderLockManager.tryLock(riderId, orderId, LockType.URGENCY_ORDER);
        assertNotNull(lock);
        assertEquals(LockType.URGENCY_ORDER, lock.getLockType());

        riderLockManager.releaseLock(riderId, orderId);
        assertFalse(riderLockManager.isLocked(riderId));
    }

    @Test
    @DisplayName("不同订单紧急程度锁定超时差异 - 普通订单长超时")
    void testLockTimeoutDifference_NormalOrder() {
        String riderId = testRider.getRiderId();
        String orderId = "order_normal_001";

        RiderLockManager.RiderLock lock = riderLockManager.tryLock(riderId, orderId, LockType.NORMAL_ORDER);
        assertNotNull(lock);
        assertEquals(LockType.NORMAL_ORDER, lock.getLockType());

        riderLockManager.releaseLock(riderId, orderId);
        assertFalse(riderLockManager.isLocked(riderId));
    }

    @Test
    @DisplayName("锁获取顺序 - 先到先得")
    void testLockAcquisitionOrder() {
        String riderId = testRider.getRiderId();

        RiderLockManager.RiderLock lock1 = riderLockManager.tryLock(riderId, "order_001", LockType.NORMAL_ORDER);
        assertNotNull(lock1);
        assertEquals("order_001", lock1.getOrderId());

        RiderLockManager.RiderLock lock2 = riderLockManager.tryLock(riderId, "order_002", LockType.NORMAL_ORDER);
        assertNull(lock2);

        riderLockManager.releaseLock(riderId, "order_001");

        RiderLockManager.RiderLock lock3 = riderLockManager.tryLock(riderId, "order_002", LockType.NORMAL_ORDER);
        assertNotNull(lock3);
        assertEquals("order_002", lock3.getOrderId());
    }
}
