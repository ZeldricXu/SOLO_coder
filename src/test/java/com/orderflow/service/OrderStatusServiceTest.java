package com.orderflow.service;

import com.orderflow.config.TestConfig;
import com.orderflow.entity.Order;
import com.orderflow.entity.OrderStatusLog;
import com.orderflow.enums.OrderStatus;
import com.orderflow.exception.BusinessException;
import com.orderflow.lock.DistributedLockService;
import com.orderflow.repository.OrderRepository;
import com.orderflow.repository.OrderStatusLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Import;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Import(TestConfig.class)
@DisplayName("订单状态流转服务测试")
class OrderStatusServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusLogRepository orderStatusLogRepository;

    @Mock
    private DistributedLockService distributedLockService;

    @InjectMocks
    private OrderStatusService orderStatusService;

    private Order testOrder;
    private final String TEST_ORDER_ID = "order_test_001";

    @BeforeEach
    void setUp() {
        testOrder = new Order();
        testOrder.setOrderId(TEST_ORDER_ID);
        testOrder.setUserId("user_123");
        testOrder.setOrderNo("ORD202605050001");
        testOrder.setStatus(OrderStatus.PENDING_PAYMENT);
        testOrder.setTotalAmount(java.math.BigDecimal.valueOf(100.00));
        testOrder.setPaymentMethod("alipay");
    }

    @Test
    @DisplayName("测试状态变更合法性校验 - 合法变更")
    void testValidStatusTransition() {
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(orderRepository.findByIdWithLock(TEST_ORDER_ID))
                .thenReturn(Optional.of(testOrder));
        when(orderStatusLogRepository.save(any(OrderStatusLog.class)))
                .thenReturn(null);

        boolean result = orderStatusService.transitionStatus(
                TEST_ORDER_ID, OrderStatus.PAID, "system", "支付成功");

        assertTrue(result);
        verify(orderRepository, times(1)).save(any(Order.class));
        verify(orderStatusLogRepository, times(1)).save(any(OrderStatusLog.class));
        verify(distributedLockService, times(1)).unlock(anyString());
    }

    @Test
    @DisplayName("测试状态变更合法性校验 - 非法变更")
    void testInvalidStatusTransition() {
        testOrder.setStatus(OrderStatus.COMPLETED);

        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(orderRepository.findByIdWithLock(TEST_ORDER_ID))
                .thenReturn(Optional.of(testOrder));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            orderStatusService.transitionStatus(
                    TEST_ORDER_ID, OrderStatus.PAID, "system", "非法变更");
        });

        assertTrue(exception.getMessage().contains("订单状态变更不合法"));
        verify(orderRepository, never()).save(any(Order.class));
        verify(distributedLockService, times(1)).unlock(anyString());
    }

    @Test
    @DisplayName("测试状态变更 - 相同状态")
    void testSameStatusTransition() {
        testOrder.setStatus(OrderStatus.PAID);

        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(orderRepository.findByIdWithLock(TEST_ORDER_ID))
                .thenReturn(Optional.of(testOrder));

        boolean result = orderStatusService.transitionStatus(
                TEST_ORDER_ID, OrderStatus.PAID, "system", "相同状态");

        assertFalse(result);
        verify(orderRepository, never()).save(any(Order.class));
        verify(distributedLockService, times(1)).unlock(anyString());
    }

    @Test
    @DisplayName("测试状态变更 - 订单不存在")
    void testOrderNotFound() {
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(orderRepository.findByIdWithLock(TEST_ORDER_ID))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            orderStatusService.transitionStatus(
                    TEST_ORDER_ID, OrderStatus.PAID, "system", "测试");
        });

        assertTrue(exception.getMessage().contains("订单不存在"));
        verify(distributedLockService, times(1)).unlock(anyString());
    }

    @Test
    @DisplayName("测试分布式锁获取失败")
    void testLockAcquisitionFailure() {
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            orderStatusService.transitionStatus(
                    TEST_ORDER_ID, OrderStatus.PAID, "system", "测试");
        });

        assertTrue(exception.getMessage().contains("订单状态变更繁忙"));
        verify(orderRepository, never()).findByIdWithLock(anyString());
        verify(distributedLockService, never()).unlock(anyString());
    }

    @Test
    @DisplayName("测试状态变更日志记录")
    void testStatusLogRecording() {
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(orderRepository.findByIdWithLock(TEST_ORDER_ID))
                .thenReturn(Optional.of(testOrder));

        OrderStatusLog savedLog = new OrderStatusLog();
        savedLog.setOrderId(TEST_ORDER_ID);
        savedLog.setFromStatus(OrderStatus.PENDING_PAYMENT.getCode());
        savedLog.setToStatus(OrderStatus.PAID.getCode());
        savedLog.setOperator("system");
        savedLog.setReason("支付成功");

        when(orderStatusLogRepository.save(any(OrderStatusLog.class))).thenReturn(savedLog);

        orderStatusService.transitionStatus(TEST_ORDER_ID, OrderStatus.PAID, "system", "支付成功");

        verify(orderStatusLogRepository).save(argThat(log ->
                log.getFromStatus().equals(OrderStatus.PENDING_PAYMENT.getCode()) &&
                log.getToStatus().equals(OrderStatus.PAID.getCode()) &&
                log.getOperator().equals("system") &&
                log.getReason().equals("支付成功")
        ));
    }

    @Test
    @DisplayName("测试状态流转规则验证 - 待支付 -> 已支付")
    void testPendingToPaidTransition() {
        assertTrue(orderStatusService.isValidTransition(
                OrderStatus.PENDING_PAYMENT, OrderStatus.PAID));
    }

    @Test
    @DisplayName("测试状态流转规则验证 - 待支付 -> 已取消")
    void testPendingToCancelledTransition() {
        assertTrue(orderStatusService.isValidTransition(
                OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED));
    }

    @Test
    @DisplayName("测试状态流转规则验证 - 已支付 -> 已发货")
    void testPaidToShippedTransition() {
        assertTrue(orderStatusService.isValidTransition(
                OrderStatus.PAID, OrderStatus.SHIPPED));
    }

    @Test
    @DisplayName("测试状态流转规则验证 - 已支付 -> 退款中")
    void testPaidToRefundingTransition() {
        assertTrue(orderStatusService.isValidTransition(
                OrderStatus.PAID, OrderStatus.REFUNDING));
    }

    @Test
    @DisplayName("测试状态流转规则验证 - 非法变更：已完成 -> 已支付")
    void testInvalidCompletedToPaid() {
        assertFalse(orderStatusService.isValidTransition(
                OrderStatus.COMPLETED, OrderStatus.PAID));
    }

    @Test
    @DisplayName("测试状态流转规则验证 - 非法变更：已取消 -> 已支付")
    void testInvalidCancelledToPaid() {
        assertFalse(orderStatusService.isValidTransition(
                OrderStatus.CANCELLED, OrderStatus.PAID));
    }

    @Test
    @DisplayName("测试并发状态变更处理 - 无等待模式")
    void testConcurrentTransitionNoWait() throws InterruptedException {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        when(distributedLockService.tryLockNoWait(anyString(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    if (successCount.get() == 0) {
                        successCount.incrementAndGet();
                        return true;
                    }
                    return false;
                });

        when(orderRepository.findByIdWithLock(TEST_ORDER_ID))
                .thenReturn(Optional.of(testOrder));

        when(orderStatusLogRepository.save(any(OrderStatusLog.class)))
                .thenReturn(null);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    boolean result = orderStatusService.transitionStatusNoWait(
                            TEST_ORDER_ID, OrderStatus.PAID, "thread_" + index, "并发测试");
                    if (result) {
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
        endLatch.await(10, TimeUnit.SECONDS);

        assertEquals(threadCount, successCount.get() + failCount.get());
    }

    @Test
    @DisplayName("测试无等待锁模式 - 锁被占用时返回false")
    void testTransitionStatusNoWaitLockBusy() {
        when(distributedLockService.tryLockNoWait(anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        boolean result = orderStatusService.transitionStatusNoWait(
                TEST_ORDER_ID, OrderStatus.PAID, "system", "测试");

        assertFalse(result);
        verify(orderRepository, never()).findByIdWithLock(anyString());
    }

    @Test
    @DisplayName("测试时间戳更新 - 支付状态")
    void testTimestampUpdatePaid() {
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(orderRepository.findByIdWithLock(TEST_ORDER_ID))
                .thenReturn(Optional.of(testOrder));

        orderStatusService.transitionStatus(TEST_ORDER_ID, OrderStatus.PAID, "system", "支付成功");

        verify(orderRepository).save(argThat(order ->
                order.getPaidAt() != null
        ));
    }

    @Test
    @DisplayName("测试时间戳更新 - 发货状态")
    void testTimestampUpdateShipped() {
        testOrder.setStatus(OrderStatus.PAID);

        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(orderRepository.findByIdWithLock(TEST_ORDER_ID))
                .thenReturn(Optional.of(testOrder));

        orderStatusService.transitionStatus(TEST_ORDER_ID, OrderStatus.SHIPPED, "system", "发货成功");

        verify(orderRepository).save(argThat(order ->
                order.getShippedAt() != null
        ));
    }

    @Test
    @DisplayName("测试时间戳更新 - 完成状态")
    void testTimestampUpdateCompleted() {
        testOrder.setStatus(OrderStatus.SHIPPED);

        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(orderRepository.findByIdWithLock(TEST_ORDER_ID))
                .thenReturn(Optional.of(testOrder));

        orderStatusService.transitionStatus(TEST_ORDER_ID, OrderStatus.COMPLETED, "user", "确认收货");

        verify(orderRepository).save(argThat(order ->
                order.getCompletedAt() != null
        ));
    }
}
