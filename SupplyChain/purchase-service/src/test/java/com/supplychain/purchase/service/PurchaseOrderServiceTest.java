package com.supplychain.purchase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.supplychain.common.dto.OrderCreateRequest;
import com.supplychain.common.entity.PurchaseOrder;
import com.supplychain.common.enums.OrderStatus;
import com.supplychain.common.exception.BusinessException;
import com.supplychain.common.testdata.TestDataBuilder;
import com.supplychain.purchase.mapper.PurchaseOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("采购订单服务单元测试")
class PurchaseOrderServiceTest {

    @Mock
    private PurchaseOrderMapper orderMapper;

    @Mock
    private SupplierClientService supplierClientService;

    @InjectMocks
    private PurchaseOrderService purchaseOrderService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(purchaseOrderService, "urgentTimeoutMinutes", 30);
        ReflectionTestUtils.setField(purchaseOrderService, "normalTimeoutMinutes", 120);
        ReflectionTestUtils.setField(purchaseOrderService, "lowTimeoutMinutes", 480);
        purchaseOrderService.clearTimeoutNotifications();
    }

    @Nested
    @DisplayName("审批超时提醒测试")
    class ApprovalTimeoutTests {

        @Test
        @DisplayName("测试紧急订单超时阈值")
        void testUrgentOrderTimeoutThreshold() {
            int timeout = purchaseOrderService.getTimeoutMinutes("urgent_purchase");
            assertEquals(30, timeout, "紧急订单超时阈值应为30分钟");
        }

        @Test
        @DisplayName("测试普通订单超时阈值")
        void testNormalOrderTimeoutThreshold() {
            int timeout = purchaseOrderService.getTimeoutMinutes("purchase");
            assertEquals(120, timeout, "普通订单超时阈值应为120分钟");
        }

        @Test
        @DisplayName("测试低优先级订单超时阈值")
        void testLowPriorityOrderTimeoutThreshold() {
            int timeout = purchaseOrderService.getTimeoutMinutes("low_priority_purchase");
            assertEquals(480, timeout, "低优先级订单超时阈值应为480分钟");
        }

        @Test
        @DisplayName("测试订单超时检测 - 紧急订单超时")
        void testUrgentOrderTimeoutDetection() {
            PurchaseOrder order = PurchaseOrder.builder()
                    .orderId("order_urgent_test")
                    .orderType("urgent_purchase")
                    .orderStatus(OrderStatus.PENDING_APPROVAL.getCode())
                    .createdAt(LocalDateTime.now().minusMinutes(40))
                    .build();

            boolean isTimeout = purchaseOrderService.isOrderTimeout(order);
            assertTrue(isTimeout, "紧急订单等待40分钟应判定为超时");
        }

        @Test
        @DisplayName("测试订单超时检测 - 紧急订单未超时")
        void testUrgentOrderNotTimeout() {
            PurchaseOrder order = PurchaseOrder.builder()
                    .orderId("order_urgent_test2")
                    .orderType("urgent_purchase")
                    .orderStatus(OrderStatus.PENDING_APPROVAL.getCode())
                    .createdAt(LocalDateTime.now().minusMinutes(20))
                    .build();

            boolean isTimeout = purchaseOrderService.isOrderTimeout(order);
            assertFalse(isTimeout, "紧急订单等待20分钟不应判定为超时");
        }

        @Test
        @DisplayName("测试订单超时检测 - 已审批订单不检测超时")
        void testApprovedOrderNoTimeoutCheck() {
            PurchaseOrder order = PurchaseOrder.builder()
                    .orderId("order_approved_test")
                    .orderType("urgent_purchase")
                    .orderStatus(OrderStatus.CONFIRMED.getCode())
                    .createdAt(LocalDateTime.now().minusHours(5))
                    .build();

            boolean isTimeout = purchaseOrderService.isOrderTimeout(order);
            assertFalse(isTimeout, "已审批订单不应进行超时检测");
        }

        @Test
        @DisplayName("测试不同紧急程度订单的超时阈值差异")
        void testDifferentPriorityThresholds() {
            int urgent = purchaseOrderService.getTimeoutMinutes("urgent_purchase");
            int normal = purchaseOrderService.getTimeoutMinutes("standard_purchase");
            int low = purchaseOrderService.getTimeoutMinutes("low_priority");

            assertTrue(urgent < normal, "紧急订单超时阈值应小于普通订单");
            assertTrue(normal < low, "普通订单超时阈值应小于低优先级订单");
            assertEquals(30, urgent);
            assertEquals(120, normal);
            assertEquals(480, low);
        }

        @Test
        @DisplayName("测试超时提醒发送")
        void testTimeoutNotificationSent() {
            PurchaseOrder pendingUrgent = PurchaseOrder.builder()
                    .orderId("order_timeout_001")
                    .orderType("urgent_purchase")
                    .orderStatus(OrderStatus.PENDING_APPROVAL.getCode())
                    .createdAt(LocalDateTime.now().minusMinutes(45))
                    .build();

            PurchaseOrder pendingNormal = PurchaseOrder.builder()
                    .orderId("order_timeout_002")
                    .orderType("purchase")
                    .orderStatus(OrderStatus.PENDING_APPROVAL.getCode())
                    .createdAt(LocalDateTime.now().minusMinutes(45))
                    .build();

            List<PurchaseOrder> pendingOrders = Arrays.asList(pendingUrgent, pendingNormal);
            when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(pendingOrders);

            purchaseOrderService.checkApprovalTimeout();

            List<String> notifications = purchaseOrderService.getTimeoutNotifications();
            assertEquals(1, notifications.size(), "应只发送紧急订单的超时提醒");
            assertTrue(notifications.get(0).contains("order_timeout_001"));
        }
    }

    @Nested
    @DisplayName("供应商资质校验测试")
    class SupplierValidationTests {

        @Test
        @DisplayName("测试创建订单时供应商资质校验 - 合格供应商")
        void testCreateOrderWithQualifiedSupplier() {
            OrderCreateRequest request = TestDataBuilder.buildOrderCreateRequest("supplier_qual_001");
            
            doNothing().when(supplierClientService).validateSupplier("supplier_qual_001");
            when(orderMapper.insert(any(PurchaseOrder.class))).thenReturn(1);

            PurchaseOrder order = purchaseOrderService.createOrder(request);

            assertNotNull(order);
            assertNotNull(order.getOrderId());
            assertEquals("supplier_qual_001", order.getSupplierId());
            assertEquals(OrderStatus.PENDING_APPROVAL.getCode(), order.getOrderStatus());
            verify(supplierClientService, times(1)).validateSupplier("supplier_qual_001");
        }

        @Test
        @DisplayName("测试创建订单时供应商资质校验 - 不合格供应商")
        void testCreateOrderWithDisqualifiedSupplier() {
            OrderCreateRequest request = TestDataBuilder.buildOrderCreateRequest("supplier_disq_001");
            
            doThrow(new BusinessException("供应商资质无效"))
                .when(supplierClientService).validateSupplier("supplier_disq_001");

            assertThrows(BusinessException.class, () -> {
                purchaseOrderService.createOrder(request);
            }, "不合格供应商创建订单应抛出异常");

            verify(orderMapper, never()).insert(any(PurchaseOrder.class));
        }

        @Test
        @DisplayName("测试创建订单时未指定供应商")
        void testCreateOrderWithoutSupplier() {
            OrderCreateRequest request = OrderCreateRequest.builder()
                    .supplierId("")
                    .orderItems(TestDataBuilder.buildOrderItemRequests(2))
                    .build();

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                purchaseOrderService.createOrder(request);
            });

            assertEquals("请指定供应商", exception.getMessage());
            verify(supplierClientService, never()).validateSupplier(anyString());
            verify(orderMapper, never()).insert(any(PurchaseOrder.class));
        }

        @Test
        @DisplayName("测试供应商资质校验方法被正确调用")
        void testSupplierValidationCalled() {
            String supplierId = "supplier_test_001";
            OrderCreateRequest request = TestDataBuilder.buildOrderCreateRequest(supplierId);
            
            doNothing().when(supplierClientService).validateSupplier(supplierId);
            when(orderMapper.insert(any(PurchaseOrder.class))).thenReturn(1);

            purchaseOrderService.createOrder(request);

            ArgumentCaptor<String> supplierIdCaptor = ArgumentCaptor.forClass(String.class);
            verify(supplierClientService).validateSupplier(supplierIdCaptor.capture());
            assertEquals(supplierId, supplierIdCaptor.getValue());
        }
    }

    @Nested
    @DisplayName("订单状态流转测试")
    class OrderStatusFlowTests {

        @Test
        @DisplayName("测试有效状态流转 - 待审批到已确认")
        void testValidStatusTransitionPendingToConfirmed() {
            boolean valid = purchaseOrderService.isValidStatusTransition(
                    OrderStatus.PENDING_APPROVAL.getCode(),
                    OrderStatus.CONFIRMED.getCode());
            assertTrue(valid, "待审批到已确认是有效流转");
        }

        @Test
        @DisplayName("测试有效状态流转 - 待审批到已拒绝")
        void testValidStatusTransitionPendingToRejected() {
            boolean valid = purchaseOrderService.isValidStatusTransition(
                    OrderStatus.PENDING_APPROVAL.getCode(),
                    OrderStatus.REJECTED.getCode());
            assertTrue(valid, "待审批到已拒绝是有效流转");
        }

        @Test
        @DisplayName("测试有效状态流转 - 已确认到已收货")
        void testValidStatusTransitionConfirmedToReceived() {
            boolean valid = purchaseOrderService.isValidStatusTransition(
                    OrderStatus.CONFIRMED.getCode(),
                    OrderStatus.RECEIVED.getCode());
            assertTrue(valid, "已确认到已收货是有效流转");
        }

        @Test
        @DisplayName("测试有效状态流转 - 已发货到已收货")
        void testValidStatusTransitionShippedToReceived() {
            boolean valid = purchaseOrderService.isValidStatusTransition(
                    OrderStatus.SHIPPED.getCode(),
                    OrderStatus.RECEIVED.getCode());
            assertTrue(valid, "已发货到已收货是有效流转");
        }

        @Test
        @DisplayName("测试无效状态流转 - 待审批到已收货")
        void testInvalidStatusTransitionPendingToReceived() {
            boolean valid = purchaseOrderService.isValidStatusTransition(
                    OrderStatus.PENDING_APPROVAL.getCode(),
                    OrderStatus.RECEIVED.getCode());
            assertFalse(valid, "待审批直接到已收货是无效流转");
        }

        @Test
        @DisplayName("测试无效状态流转 - 已收货到已确认")
        void testInvalidStatusTransitionReceivedToConfirmed() {
            boolean valid = purchaseOrderService.isValidStatusTransition(
                    OrderStatus.RECEIVED.getCode(),
                    OrderStatus.CONFIRMED.getCode());
            assertFalse(valid, "已收货回退到已确认是无效流转");
        }

        @Test
        @DisplayName("测试订单完整状态流转链")
        void testCompleteOrderStatusFlow() {
            PurchaseOrder order = TestDataBuilder.buildPendingApprovalOrder();
            order.setOrderId("order_flow_test");
            
            when(orderMapper.selectById("order_flow_test")).thenReturn(order);
            when(orderMapper.updateById(any(PurchaseOrder.class))).thenReturn(1);

            PurchaseOrder confirmedOrder = purchaseOrderService.transitToStatus(
                    "order_flow_test", OrderStatus.CONFIRMED);
            assertEquals(OrderStatus.CONFIRMED.getCode(), confirmedOrder.getOrderStatus());
            assertNotNull(confirmedOrder.getConfirmedAt());

            order.setOrderStatus(OrderStatus.CONFIRMED.getCode());
            order.setConfirmedAt(LocalDateTime.now());
            
            when(orderMapper.selectById("order_flow_test")).thenReturn(order);
            
            PurchaseOrder receivedOrder = purchaseOrderService.transitToStatus(
                    "order_flow_test", OrderStatus.RECEIVED);
            assertEquals(OrderStatus.RECEIVED.getCode(), receivedOrder.getOrderStatus());
            assertNotNull(receivedOrder.getReceivedAt());

            order.setOrderStatus(OrderStatus.RECEIVED.getCode());
            order.setReceivedAt(LocalDateTime.now());
            
            when(orderMapper.selectById("order_flow_test")).thenReturn(order);
            
            PurchaseOrder completedOrder = purchaseOrderService.transitToStatus(
                    "order_flow_test", OrderStatus.COMPLETED);
            assertEquals(OrderStatus.COMPLETED.getCode(), completedOrder.getOrderStatus());
        }

        @Test
        @DisplayName("测试审批通过后状态更新")
        void testApproveOrderUpdatesStatus() {
            PurchaseOrder pendingOrder = TestDataBuilder.buildPendingApprovalOrder();
            pendingOrder.setOrderId("order_approve_test");
            
            when(orderMapper.selectById("order_approve_test")).thenReturn(pendingOrder);
            when(orderMapper.updateById(any(PurchaseOrder.class))).thenReturn(1);

            PurchaseOrder approvedOrder = purchaseOrderService.approveOrder(
                    "order_approve_test", "审批人张三");

            assertEquals(OrderStatus.CONFIRMED.getCode(), approvedOrder.getOrderStatus());
            assertEquals("审批人张三", approvedOrder.getApprover());
            assertNotNull(approvedOrder.getConfirmedAt());
        }

        @Test
        @DisplayName("测试审批拒绝后状态更新")
        void testRejectOrderUpdatesStatus() {
            PurchaseOrder pendingOrder = TestDataBuilder.buildPendingApprovalOrder();
            pendingOrder.setOrderId("order_reject_test");
            
            when(orderMapper.selectById("order_reject_test")).thenReturn(pendingOrder);
            when(orderMapper.updateById(any(PurchaseOrder.class))).thenReturn(1);

            PurchaseOrder rejectedOrder = purchaseOrderService.rejectOrder(
                    "order_reject_test", "审批人李四", "价格过高");

            assertEquals(OrderStatus.REJECTED.getCode(), rejectedOrder.getOrderStatus());
            assertEquals("审批人李四", rejectedOrder.getApprover());
            assertEquals("价格过高", rejectedOrder.getRejectReason());
        }

        @Test
        @DisplayName("测试非待审批状态无法审批")
        void testCannotApproveNonPendingOrder() {
            PurchaseOrder confirmedOrder = TestDataBuilder.buildConfirmedOrder();
            confirmedOrder.setOrderId("order_confirmed_test");
            
            when(orderMapper.selectById("order_confirmed_test")).thenReturn(confirmedOrder);

            assertThrows(BusinessException.class, () -> {
                purchaseOrderService.approveOrder("order_confirmed_test", "审批人");
            }, "已确认订单不应再次审批");

            assertThrows(BusinessException.class, () -> {
                purchaseOrderService.rejectOrder("order_confirmed_test", "审批人", "理由");
            }, "已确认订单不应被拒绝");
        }

        @Test
        @DisplayName("测试获取审批状态流")
        void testGetApprovalStatusFlow() {
            PurchaseOrder pendingOrder = TestDataBuilder.buildPendingApprovalOrder();
            pendingOrder.setOrderId("order_flow_test2");
            
            when(orderMapper.selectById("order_flow_test2")).thenReturn(pendingOrder);

            Map<String, Object> flow = purchaseOrderService.getApprovalStatusFlow("order_flow_test2");

            assertEquals("order_flow_test2", flow.get("orderId"));
            assertEquals(OrderStatus.PENDING_APPROVAL.getCode(), flow.get("currentStatus"));
            
            @SuppressWarnings("unchecked")
            List<String> validTransitions = (List<String>) flow.get("validNextStatuses");
            assertTrue(validTransitions.contains(OrderStatus.CONFIRMED.getCode()));
            assertTrue(validTransitions.contains(OrderStatus.REJECTED.getCode()));
            assertFalse(validTransitions.contains(OrderStatus.RECEIVED.getCode()));
        }

        @Test
        @DisplayName("测试已确认订单的有效流转状态")
        void testGetConfirmedOrderStatusFlow() {
            PurchaseOrder confirmedOrder = TestDataBuilder.buildConfirmedOrder();
            confirmedOrder.setOrderId("order_confirmed_flow");
            
            when(orderMapper.selectById("order_confirmed_flow")).thenReturn(confirmedOrder);

            Map<String, Object> flow = purchaseOrderService.getApprovalStatusFlow("order_confirmed_flow");

            assertEquals(OrderStatus.CONFIRMED.getCode(), flow.get("currentStatus"));
            
            @SuppressWarnings("unchecked")
            List<String> validTransitions = (List<String>) flow.get("validNextStatuses");
            assertTrue(validTransitions.contains(OrderStatus.SHIPPED.getCode()));
            assertTrue(validTransitions.contains(OrderStatus.RECEIVED.getCode()));
            assertFalse(validTransitions.contains(OrderStatus.PENDING_APPROVAL.getCode()));
        }
    }

    @Nested
    @DisplayName("订单创建测试")
    class OrderCreationTests {

        @Test
        @DisplayName("测试创建订单时金额计算")
        void testOrderAmountCalculation() {
            OrderCreateRequest request = OrderCreateRequest.builder()
                    .supplierId("supplier_qual_001")
                    .orderItems(Arrays.asList(
                            com.supplychain.common.dto.OrderItemRequest.builder()
                                    .itemId("item_001")
                                    .itemName("商品1")
                                    .quantity(100)
                                    .price(new java.math.BigDecimal("50.00"))
                                    .build(),
                            com.supplychain.common.dto.OrderItemRequest.builder()
                                    .itemId("item_002")
                                    .itemName("商品2")
                                    .quantity(200)
                                    .price(new java.math.BigDecimal("30.00"))
                                    .build()
                    ))
                    .build();

            doNothing().when(supplierClientService).validateSupplier("supplier_qual_001");
            when(orderMapper.insert(any(PurchaseOrder.class))).thenReturn(1);

            PurchaseOrder order = purchaseOrderService.createOrder(request);

            assertEquals(new java.math.BigDecimal("11000.00"), order.getOrderAmount());
            assertEquals(2, order.getOrderItems().size());
        }

        @Test
        @DisplayName("测试创建订单时状态设置")
        void testOrderStatusOnCreation() {
            OrderCreateRequest request = TestDataBuilder.buildOrderCreateRequest("supplier_qual_001");
            
            doNothing().when(supplierClientService).validateSupplier("supplier_qual_001");
            when(orderMapper.insert(any(PurchaseOrder.class))).thenReturn(1);

            PurchaseOrder order = purchaseOrderService.createOrder(request);

            assertEquals(OrderStatus.PENDING_APPROVAL.getCode(), order.getOrderStatus());
            assertNull(order.getApprover());
            assertNull(order.getConfirmedAt());
            assertNotNull(order.getCreatedAt());
        }

        @Test
        @DisplayName("测试创建订单时ID生成")
        void testOrderIdGeneration() {
            OrderCreateRequest request = TestDataBuilder.buildOrderCreateRequest("supplier_qual_001");
            
            doNothing().when(supplierClientService).validateSupplier("supplier_qual_001");
            when(orderMapper.insert(any(PurchaseOrder.class))).thenReturn(1);

            PurchaseOrder order = purchaseOrderService.createOrder(request);

            assertNotNull(order.getOrderId());
            assertTrue(order.getOrderId().startsWith("order_"));
        }
    }

    @Nested
    @DisplayName("订单查询测试")
    class OrderQueryTests {

        @Test
        @DisplayName("测试获取存在的订单")
        void testGetExistingOrder() {
            PurchaseOrder order = TestDataBuilder.buildConfirmedOrder();
            order.setOrderId("order_query_test");
            
            when(orderMapper.selectById("order_query_test")).thenReturn(order);

            PurchaseOrder result = purchaseOrderService.getOrder("order_query_test");

            assertNotNull(result);
            assertEquals("order_query_test", result.getOrderId());
        }

        @Test
        @DisplayName("测试获取不存在的订单")
        void testGetNonExistingOrder() {
            when(orderMapper.selectById("order_not_exist")).thenReturn(null);

            BusinessException exception = assertThrows(BusinessException.class, () -> {
                purchaseOrderService.getOrder("order_not_exist");
            });

            assertEquals(404, exception.getCode());
            assertEquals("采购订单不存在", exception.getMessage());
        }

        @Test
        @DisplayName("测试按状态查询订单列表")
        void testListOrdersByStatus() {
            List<PurchaseOrder> pendingOrders = Arrays.asList(
                    TestDataBuilder.buildPendingApprovalOrder(),
                    TestDataBuilder.buildPendingApprovalOrder()
            );

            when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(pendingOrders);

            List<PurchaseOrder> result = purchaseOrderService.listOrders(
                    OrderStatus.PENDING_APPROVAL.getCode(), null);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("测试按供应商查询订单列表")
        void testListOrdersBySupplier() {
            List<PurchaseOrder> supplierOrders = Arrays.asList(
                    TestDataBuilder.buildConfirmedOrder()
            );

            when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(supplierOrders);

            List<PurchaseOrder> result = purchaseOrderService.listOrders(
                    null, "supplier_qual_001");

            assertEquals(1, result.size());
        }
    }
}
