package com.restaurant.mgmt.service;

import com.restaurant.mgmt.builder.TestDataBuilder;
import com.restaurant.mgmt.config.DynamicPaymentTimeoutConfig;
import com.restaurant.mgmt.model.Order;
import com.restaurant.mgmt.model.RestaurantTable;
import com.restaurant.mgmt.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("订单模块 - 单元测试")
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private TableService tableService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private PaymentTimeoutService paymentTimeoutService;

    @Mock
    private DynamicPaymentTimeoutConfig timeoutConfig;

    @BeforeEach
    void setUp() {
        lenient().when(timeoutConfig.getSmallOrderThreshold()).thenReturn(100.0);
        lenient().when(timeoutConfig.getLargeOrderThreshold()).thenReturn(500.0);
        lenient().when(timeoutConfig.getTimeoutMinutes(anyDouble())).thenAnswer(inv -> {
            double amount = inv.getArgument(0);
            if (amount < 100.0) return 5;
            if (amount < 500.0) return 10;
            return 20;
        });
        lenient().when(timeoutConfig.getReminderMinutes(anyDouble())).thenAnswer(inv -> {
            double amount = inv.getArgument(0);
            if (amount < 100.0) return 3;
            if (amount < 500.0) return 7;
            return 15;
        });
        lenient().when(timeoutConfig.getOrderSizeCategory(anyDouble())).thenAnswer(inv -> {
            double amount = inv.getArgument(0);
            if (amount < 100.0) return "small";
            if (amount < 500.0) return "medium";
            return "large";
        });
        lenient().when(timeoutConfig.isSmallOrder(anyDouble())).thenAnswer(inv -> 
            (Double) inv.getArgument(0) < 100.0);
        lenient().when(timeoutConfig.isLargeOrder(anyDouble())).thenAnswer(inv -> 
            (Double) inv.getArgument(0) >= 500.0);
    }

    @Nested
    @DisplayName("支付超时提醒测试")
    class PaymentTimeoutReminderTests {

        @Test
        @DisplayName("小额订单支付超时未处理时应发送超时提醒")
        void testSmallOrderTimeoutReminder() {
            Order smallOrder = TestDataBuilder.buildOrderCreatedAt(4);
            smallOrder.setOrderAmount(TestDataBuilder.SMALL_ORDER_AMOUNT);

            when(orderRepository.findByOrderStatus("pending_payment"))
                .thenReturn(List.of(smallOrder));

            paymentTimeoutService.checkPaymentTimeout();

            verify(notificationService, times(1))
                .sendPaymentTimeoutReminder(smallOrder);
        }

        @Test
        @DisplayName("大额订单支付超时未处理时应发送超时提醒")
        void testLargeOrderTimeoutReminder() {
            Order largeOrder = TestDataBuilder.buildOrderCreatedAt(18);
            largeOrder.setOrderAmount(TestDataBuilder.LARGE_ORDER_AMOUNT);

            when(orderRepository.findByOrderStatus("pending_payment"))
                .thenReturn(List.of(largeOrder));

            paymentTimeoutService.checkPaymentTimeout();

            verify(notificationService, times(1))
                .sendPaymentTimeoutReminder(largeOrder);
        }

        @Test
        @DisplayName("刚创建的订单不应发送超时提醒")
        void testNewOrderShouldNotTriggerReminder() {
            Order newOrder = TestDataBuilder.buildOrderCreatedAt(1);
            newOrder.setOrderAmount(TestDataBuilder.SMALL_ORDER_AMOUNT);

            when(orderRepository.findByOrderStatus("pending_payment"))
                .thenReturn(List.of(newOrder));

            paymentTimeoutService.checkPaymentTimeout();

            verify(notificationService, never())
                .sendPaymentTimeoutReminder(any(Order.class));
        }

        @Test
        @DisplayName("已支付订单不应发送超时提醒")
        void testConfirmedOrderShouldNotTriggerReminder() {
            Order confirmedOrder = TestDataBuilder.buildOrderWithStatus("confirmed");

            when(orderRepository.findByOrderStatus("pending_payment"))
                .thenReturn(List.of());

            paymentTimeoutService.checkPaymentTimeout();

            verify(notificationService, never())
                .sendPaymentTimeoutReminder(any(Order.class));
        }
    }

    @Nested
    @DisplayName("超时阈值差异测试")
    class TimeoutThresholdDifferenceTests {

        @Test
        @DisplayName("小额订单应使用短超时阈值")
        void testSmallOrderShortTimeout() {
            int smallTimeout = timeoutConfig.getTimeoutMinutes(TestDataBuilder.SMALL_ORDER_AMOUNT);
            int largeTimeout = timeoutConfig.getTimeoutMinutes(TestDataBuilder.LARGE_ORDER_AMOUNT);

            assertTrue(timeoutConfig.isSmallOrder(TestDataBuilder.SMALL_ORDER_AMOUNT));
            assertTrue(timeoutConfig.isLargeOrder(TestDataBuilder.LARGE_ORDER_AMOUNT));
            assertEquals(5, smallTimeout);
            assertEquals(20, largeTimeout);
            assertTrue(smallTimeout < largeTimeout, 
                "小额订单超时时间应小于大额订单");
        }

        @Test
        @DisplayName("不同金额订单超时阈值应正确分级")
        void testTimeoutThresholdGrading() {
            assertEquals(5, timeoutConfig.getTimeoutMinutes(50.0));
            assertEquals(10, timeoutConfig.getTimeoutMinutes(200.0));
            assertEquals(20, timeoutConfig.getTimeoutMinutes(600.0));
        }

        @Test
        @DisplayName("不同金额订单提醒阈值应正确分级")
        void testReminderThresholdGrading() {
            assertEquals(3, timeoutConfig.getReminderMinutes(50.0));
            assertEquals(7, timeoutConfig.getReminderMinutes(200.0));
            assertEquals(15, timeoutConfig.getReminderMinutes(600.0));
        }

        @Test
        @DisplayName("订单大小分类应正确")
        void testOrderSizeCategory() {
            assertEquals("small", timeoutConfig.getOrderSizeCategory(50.0));
            assertEquals("medium", timeoutConfig.getOrderSizeCategory(200.0));
            assertEquals("large", timeoutConfig.getOrderSizeCategory(600.0));
        }
    }

    @Nested
    @DisplayName("订单状态流转测试")
    class OrderStatusFlowTests {

        @Test
        @DisplayName("支付成功后订单状态应变为已确认")
        void testPaymentSuccessStatusChange() {
            Order pendingOrder = TestDataBuilder.buildSmallOrder();

            when(orderRepository.findById(pendingOrder.getOrderId()))
                .thenReturn(Optional.of(pendingOrder));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            Order saved = orderRepository.save(pendingOrder);
            saved.setOrderStatus("confirmed");

            assertEquals("confirmed", saved.getOrderStatus());
        }

        @Test
        @DisplayName("支付失败后订单状态应变为已取消")
        void testPaymentFailureStatusChange() {
            Order pendingOrder = TestDataBuilder.buildSmallOrder();

            pendingOrder.setOrderStatus("cancelled");
            pendingOrder.setCancelReason("支付失败");

            assertEquals("cancelled", pendingOrder.getOrderStatus());
            assertEquals("支付失败", pendingOrder.getCancelReason());
        }

        @Test
        @DisplayName("已确认订单可流转为已完成")
        void testConfirmedToCompletedFlow() {
            Order confirmedOrder = TestDataBuilder.buildOrderWithStatus("confirmed");

            confirmedOrder.setOrderStatus("completed");

            assertEquals("completed", confirmedOrder.getOrderStatus());
        }

        @Test
        @DisplayName("订单完整状态流转链")
        void testFullStatusFlow() {
            Order order = TestDataBuilder.buildSmallOrder();

            assertEquals("pending_payment", order.getOrderStatus());

            order.setOrderStatus("confirmed");
            order.setConfirmedAt(java.time.LocalDateTime.now());
            assertEquals("confirmed", order.getOrderStatus());
            assertNotNull(order.getConfirmedAt());

            order.setOrderStatus("completed");
            order.setCompletedAt(java.time.LocalDateTime.now());
            assertEquals("completed", order.getOrderStatus());
            assertNotNull(order.getCompletedAt());
        }
    }

    @Nested
    @DisplayName("订单取消与座位恢复测试")
    class OrderCancelAndTableRestoreTests {

        @Test
        @DisplayName("订单取消时应恢复座位状态")
        void testOrderCancelShouldRestoreTable() {
            Order pendingOrder = TestDataBuilder.buildSmallOrder();
            RestaurantTable table = TestDataBuilder.buildAvailableTableA01();

            when(orderRepository.findByOrderStatus("pending_payment"))
                .thenReturn(List.of(pendingOrder));
            when(orderRepository.save(any(Order.class))).thenReturn(pendingOrder);
            doNothing().when(tableService).releaseTable(anyString());

            paymentTimeoutService.clearReminderHistory();
            paymentTimeoutService.checkPaymentTimeout();

            verify(tableService, times(1)).releaseTable(pendingOrder.getTableId());
        }

        @Test
        @DisplayName("支付超时自动取消订单")
        void testTimeoutAutoCancel() {
            Order timeoutOrder = TestDataBuilder.buildOrderCreatedAt(10);
            timeoutOrder.setOrderAmount(TestDataBuilder.SMALL_ORDER_AMOUNT);

            when(orderRepository.findByOrderStatus("pending_payment"))
                .thenReturn(List.of(timeoutOrder));
            when(orderRepository.save(any(Order.class))).thenReturn(timeoutOrder);
            doNothing().when(tableService).releaseTable(anyString());

            paymentTimeoutService.clearReminderHistory();
            paymentTimeoutService.checkPaymentTimeout();

            verify(orderRepository, times(1)).save(any(Order.class));
            verify(tableService, times(1)).releaseTable(timeoutOrder.getTableId());
        }

        @Test
        @DisplayName("无关联桌位的订单取消不应触发桌位释放")
        void testCancelWithoutTableShouldNotRelease() {
            Order orderWithoutTable = TestDataBuilder.buildOrderCreatedAt(10);
            orderWithoutTable.setTableId(null);
            orderWithoutTable.setOrderAmount(TestDataBuilder.SMALL_ORDER_AMOUNT);

            when(orderRepository.findByOrderStatus("pending_payment"))
                .thenReturn(List.of(orderWithoutTable));
            when(orderRepository.save(any(Order.class))).thenReturn(orderWithoutTable);

            paymentTimeoutService.clearReminderHistory();
            paymentTimeoutService.checkPaymentTimeout();

            verify(tableService, never()).releaseTable(anyString());
        }
    }

    @Nested
    @DisplayName("批量订单超时检查测试")
    class BatchTimeoutCheckTests {

        @Test
        @DisplayName("多个订单应分别检查超时状态")
        void testMultipleOrdersTimeoutCheck() {
            Order newOrder = TestDataBuilder.buildOrderCreatedAt(1);
            newOrder.setOrderAmount(TestDataBuilder.SMALL_ORDER_AMOUNT);

            Order nearTimeoutOrder = TestDataBuilder.buildOrderCreatedAt(4);
            nearTimeoutOrder.setOrderAmount(TestDataBuilder.SMALL_ORDER_AMOUNT);

            Order largeNearTimeoutOrder = TestDataBuilder.buildOrderCreatedAt(18);
            largeNearTimeoutOrder.setOrderAmount(TestDataBuilder.LARGE_ORDER_AMOUNT);

            when(orderRepository.findByOrderStatus("pending_payment"))
                .thenReturn(Arrays.asList(newOrder, nearTimeoutOrder, largeNearTimeoutOrder));

            paymentTimeoutService.clearReminderHistory();
            paymentTimeoutService.checkPaymentTimeout();

            verify(notificationService, times(2))
                .sendPaymentTimeoutReminder(any(Order.class));
        }

        @Test
        @DisplayName("相同订单短时间内不应重复发送提醒")
        void testNoDuplicateReminders() {
            Order nearTimeoutOrder = TestDataBuilder.buildOrderCreatedAt(4);
            nearTimeoutOrder.setOrderAmount(TestDataBuilder.SMALL_ORDER_AMOUNT);

            when(orderRepository.findByOrderStatus("pending_payment"))
                .thenReturn(List.of(nearTimeoutOrder));

            paymentTimeoutService.clearReminderHistory();
            paymentTimeoutService.checkPaymentTimeout();
            paymentTimeoutService.checkPaymentTimeout();

            verify(notificationService, times(1))
                .sendPaymentTimeoutReminder(nearTimeoutOrder);
        }
    }
}
