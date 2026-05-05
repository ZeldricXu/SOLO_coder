package com.orderflow.service;

import com.orderflow.config.TestConfig;
import com.orderflow.dto.RefundApplyRequest;
import com.orderflow.entity.Order;
import com.orderflow.entity.Payment;
import com.orderflow.entity.Refund;
import com.orderflow.enums.OrderStatus;
import com.orderflow.enums.PaymentStatus;
import com.orderflow.enums.RefundStatus;
import com.orderflow.exception.BusinessException;
import com.orderflow.repository.OrderRepository;
import com.orderflow.repository.PaymentRepository;
import com.orderflow.repository.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Import(TestConfig.class)
@DisplayName("退款服务测试")
class RefundServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrderStatusService orderStatusService;

    @InjectMocks
    private RefundService refundService;

    private Order testOrder;
    private Payment successPayment;
    private Refund testRefund;
    private final String TEST_ORDER_ID = "order_test_001";
    private final String TEST_REFUND_ID = "refund_test_001";
    private final String TEST_PAYMENT_ID = "payment_test_001";

    @BeforeEach
    void setUp() {
        testOrder = new Order();
        testOrder.setOrderId(TEST_ORDER_ID);
        testOrder.setUserId("user_123");
        testOrder.setOrderNo("ORD202605050001");
        testOrder.setStatus(OrderStatus.PAID);
        testOrder.setTotalAmount(new BigDecimal("100.00"));
        testOrder.setPaymentMethod("alipay");

        successPayment = new Payment();
        successPayment.setPaymentId(TEST_PAYMENT_ID);
        successPayment.setOrderId(TEST_ORDER_ID);
        successPayment.setPaymentMethod("alipay");
        successPayment.setPaymentAmount(new BigDecimal("100.00"));
        successPayment.setStatus(PaymentStatus.SUCCESS);
        successPayment.setTransactionId("txn_202605050001");
        successPayment.setPaidAt(LocalDateTime.now());

        testRefund = new Refund();
        testRefund.setRefundId(TEST_REFUND_ID);
        testRefund.setOrderId(TEST_ORDER_ID);
        testRefund.setRefundAmount(new BigDecimal("100.00"));
        testRefund.setRefundReason("商品质量问题");
        testRefund.setStatus(RefundStatus.PROCESSING);
        testRefund.setCreatedAt(LocalDateTime.now());
    }

    private RefundApplyRequest createTestRefundRequest() {
        RefundApplyRequest request = new RefundApplyRequest();
        request.setOrderId(TEST_ORDER_ID);
        request.setRefundAmount(new BigDecimal("100.00"));
        request.setRefundReason("商品质量问题");
        return request;
    }

    @Test
    @DisplayName("测试退款申请校验 - 订单不存在")
    void testApplyRefund_OrderNotFound() {
        RefundApplyRequest request = createTestRefundRequest();

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            refundService.applyRefund(request);
        });

        assertTrue(exception.getMessage().contains("订单不存在"));
    }

    @Test
    @DisplayName("测试退款申请校验 - 订单状态不支持退款")
    void testApplyRefund_InvalidOrderStatus() {
        RefundApplyRequest request = createTestRefundRequest();
        testOrder.setStatus(OrderStatus.CANCELLED);

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            refundService.applyRefund(request);
        });

        assertTrue(exception.getMessage().contains("订单状态不支持退款"));
    }

    @Test
    @DisplayName("测试退款申请校验 - 退款金额为0")
    void testApplyRefund_ZeroRefundAmount() {
        RefundApplyRequest request = createTestRefundRequest();
        request.setRefundAmount(BigDecimal.ZERO);

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            refundService.applyRefund(request);
        });

        assertTrue(exception.getMessage().contains("退款金额必须大于0"));
    }

    @Test
    @DisplayName("测试退款申请校验 - 退款金额超过订单金额")
    void testApplyRefund_AmountExceedsOrderAmount() {
        RefundApplyRequest request = createTestRefundRequest();
        request.setRefundAmount(new BigDecimal("200.00"));

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            refundService.applyRefund(request);
        });

        assertTrue(exception.getMessage().contains("退款金额不能超过订单总金额"));
    }

    @Test
    @DisplayName("测试退款申请校验 - 退款原因为空")
    void testApplyRefund_EmptyReason() {
        RefundApplyRequest request = createTestRefundRequest();
        request.setRefundReason("");

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            refundService.applyRefund(request);
        });

        assertTrue(exception.getMessage().contains("退款原因不能为空"));
    }

    @Test
    @DisplayName("测试退款申请校验 - 已有处理中的退款")
    void testApplyRefund_ExistingProcessingRefund() {
        RefundApplyRequest request = createTestRefundRequest();

        Refund processingRefund = new Refund();
        processingRefund.setRefundId("refund_002");
        processingRefund.setOrderId(TEST_ORDER_ID);
        processingRefund.setStatus(RefundStatus.PROCESSING);

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(refundRepository.findByOrderId(TEST_ORDER_ID)).thenReturn(Collections.singletonList(processingRefund));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            refundService.applyRefund(request);
        });

        assertTrue(exception.getMessage().contains("该订单已有处理中的退款申请"));
    }

    @Test
    @DisplayName("测试退款申请 - 成功创建退款")
    void testApplyRefund_Success() {
        RefundApplyRequest request = createTestRefundRequest();

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(refundRepository.findByOrderId(TEST_ORDER_ID)).thenReturn(Collections.emptyList());
        when(refundRepository.save(any(Refund.class))).thenAnswer(invocation -> {
            Refund r = invocation.getArgument(0);
            r.setRefundId(TEST_REFUND_ID);
            return r;
        });
        when(orderStatusService.transitionStatus(anyString(), any(), anyString(), anyString()))
                .thenReturn(true);

        Refund result = refundService.applyRefund(request);

        assertNotNull(result);
        assertEquals(TEST_REFUND_ID, result.getRefundId());
        assertEquals(TEST_ORDER_ID, result.getOrderId());
        assertEquals(RefundStatus.PROCESSING, result.getStatus());
        assertEquals(new BigDecimal("100.00"), result.getRefundAmount());
        assertEquals("商品质量问题", result.getRefundReason());

        verify(orderStatusService, times(1)).transitionStatus(
                eq(TEST_ORDER_ID),
                eq(OrderStatus.REFUNDING),
                eq("user"),
                anyString()
        );
    }

    @Test
    @DisplayName("测试退款申请 - 订单已是退款中状态")
    void testApplyRefund_AlreadyRefunding() {
        RefundApplyRequest request = createTestRefundRequest();
        testOrder.setStatus(OrderStatus.REFUNDING);

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(refundRepository.findByOrderId(TEST_ORDER_ID)).thenReturn(Collections.emptyList());
        when(refundRepository.save(any(Refund.class))).thenAnswer(invocation -> {
            Refund r = invocation.getArgument(0);
            r.setRefundId(TEST_REFUND_ID);
            return r;
        });

        Refund result = refundService.applyRefund(request);

        assertNotNull(result);
        verify(orderStatusService, never()).transitionStatus(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("测试退款审批 - 退款记录不存在")
    void testApproveRefund_RefundNotFound() {
        when(refundRepository.findById(TEST_REFUND_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            refundService.approveRefund(TEST_REFUND_ID);
        });

        assertTrue(exception.getMessage().contains("退款记录不存在"));
    }

    @Test
    @DisplayName("测试退款审批 - 状态不允许审批")
    void testApproveRefund_InvalidStatus() {
        testRefund.setStatus(RefundStatus.SUCCESS);

        when(refundRepository.findById(TEST_REFUND_ID)).thenReturn(Optional.of(testRefund));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            refundService.approveRefund(TEST_REFUND_ID);
        });

        assertTrue(exception.getMessage().contains("退款状态不允许审批"));
    }

    @Test
    @DisplayName("测试退款审批 - 成功")
    void testApproveRefund_Success() {
        testRefund.setStatus(RefundStatus.PROCESSING);
        testOrder.setStatus(OrderStatus.REFUNDING);

        when(refundRepository.findById(TEST_REFUND_ID)).thenReturn(Optional.of(testRefund));
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(refundRepository.save(any(Refund.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderStatusService.transitionStatus(anyString(), any(), anyString(), anyString()))
                .thenReturn(true);

        Refund result = refundService.approveRefund(TEST_REFUND_ID);

        assertNotNull(result);
        assertEquals(RefundStatus.SUCCESS, result.getStatus());
        assertNotNull(result.getProcessedAt());

        verify(orderStatusService, times(1)).transitionStatus(
                eq(TEST_ORDER_ID),
                eq(OrderStatus.REFUNDED),
                eq("system"),
                anyString()
        );
    }

    @Test
    @DisplayName("测试退款拒绝 - 退款记录不存在")
    void testRejectRefund_RefundNotFound() {
        when(refundRepository.findById(TEST_REFUND_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            refundService.rejectRefund(TEST_REFUND_ID, "不符合退款条件");
        });

        assertTrue(exception.getMessage().contains("退款记录不存在"));
    }

    @Test
    @DisplayName("测试退款拒绝 - 状态不允许拒绝")
    void testRejectRefund_InvalidStatus() {
        testRefund.setStatus(RefundStatus.SUCCESS);

        when(refundRepository.findById(TEST_REFUND_ID)).thenReturn(Optional.of(testRefund));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            refundService.rejectRefund(TEST_REFUND_ID, "不符合退款条件");
        });

        assertTrue(exception.getMessage().contains("退款状态不允许拒绝"));
    }

    @Test
    @DisplayName("测试退款拒绝 - 有成功支付，恢复到已发货状态")
    void testRejectRefund_WithShippedPayment() {
        testRefund.setStatus(RefundStatus.PROCESSING);
        testOrder.setStatus(OrderStatus.REFUNDING);
        testOrder.setShippedAt(LocalDateTime.now());

        when(refundRepository.findById(TEST_REFUND_ID)).thenReturn(Optional.of(testRefund));
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(TEST_ORDER_ID))
                .thenReturn(Optional.of(successPayment));
        when(refundRepository.save(any(Refund.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderStatusService.transitionStatus(anyString(), any(), anyString(), anyString()))
                .thenReturn(true);

        Refund result = refundService.rejectRefund(TEST_REFUND_ID, "不符合退款条件");

        assertNotNull(result);
        assertEquals(RefundStatus.REJECTED, result.getStatus());
        assertEquals("不符合退款条件", result.getFailReason());
        assertNotNull(result.getProcessedAt());

        verify(orderStatusService, times(1)).transitionStatus(
                eq(TEST_ORDER_ID),
                eq(OrderStatus.SHIPPED),
                eq("admin"),
                anyString()
        );
    }

    @Test
    @DisplayName("测试退款拒绝 - 有成功支付，恢复到已完成状态")
    void testRejectRefund_WithCompletedPayment() {
        testRefund.setStatus(RefundStatus.PROCESSING);
        testOrder.setStatus(OrderStatus.REFUNDING);
        testOrder.setCompletedAt(LocalDateTime.now());

        when(refundRepository.findById(TEST_REFUND_ID)).thenReturn(Optional.of(testRefund));
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(TEST_ORDER_ID))
                .thenReturn(Optional.of(successPayment));
        when(refundRepository.save(any(Refund.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderStatusService.transitionStatus(anyString(), any(), anyString(), anyString()))
                .thenReturn(true);

        Refund result = refundService.rejectRefund(TEST_REFUND_ID, "不符合退款条件");

        assertNotNull(result);
        verify(orderStatusService, times(1)).transitionStatus(
                eq(TEST_ORDER_ID),
                eq(OrderStatus.COMPLETED),
                eq("admin"),
                anyString()
        );
    }

    @Test
    @DisplayName("测试退款拒绝 - 无成功支付，恢复到待支付状态")
    void testRejectRefund_NoSuccessfulPayment() {
        testRefund.setStatus(RefundStatus.PROCESSING);
        testOrder.setStatus(OrderStatus.REFUNDING);

        Payment failedPayment = new Payment();
        failedPayment.setStatus(PaymentStatus.FAILED);

        when(refundRepository.findById(TEST_REFUND_ID)).thenReturn(Optional.of(testRefund));
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(TEST_ORDER_ID))
                .thenReturn(Optional.of(failedPayment));
        when(refundRepository.save(any(Refund.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderStatusService.transitionStatus(anyString(), any(), anyString(), anyString()))
                .thenReturn(true);

        Refund result = refundService.rejectRefund(TEST_REFUND_ID, "不符合退款条件");

        assertNotNull(result);
        verify(orderStatusService, times(1)).transitionStatus(
                eq(TEST_ORDER_ID),
                eq(OrderStatus.PENDING_PAYMENT),
                eq("admin"),
                anyString()
        );
    }

    @Test
    @DisplayName("测试获取退款记录")
    void testGetRefund() {
        when(refundRepository.findById(TEST_REFUND_ID)).thenReturn(Optional.of(testRefund));

        Refund result = refundService.getRefund(TEST_REFUND_ID);

        assertNotNull(result);
        assertEquals(TEST_REFUND_ID, result.getRefundId());
    }

    @Test
    @DisplayName("测试获取退款记录 - 不存在")
    void testGetRefund_NotFound() {
        when(refundRepository.findById(TEST_REFUND_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            refundService.getRefund(TEST_REFUND_ID);
        });

        assertTrue(exception.getMessage().contains("退款记录不存在"));
    }

    @Test
    @DisplayName("测试获取订单退款列表")
    void testGetRefundsByOrderId() {
        when(refundRepository.findByOrderId(TEST_ORDER_ID)).thenReturn(Collections.singletonList(testRefund));

        java.util.List<Refund> result = refundService.getRefundsByOrderId(TEST_ORDER_ID);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(TEST_REFUND_ID, result.get(0).getRefundId());
    }

    @Test
    @DisplayName("测试获取处理中的退款列表")
    void testGetProcessingRefunds() {
        when(refundRepository.findByStatus(RefundStatus.PROCESSING)).thenReturn(Collections.singletonList(testRefund));

        java.util.List<Refund> result = refundService.getProcessingRefunds();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(RefundStatus.PROCESSING, result.get(0).getStatus());
    }
}
