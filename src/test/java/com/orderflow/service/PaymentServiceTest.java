package com.orderflow.service;

import com.orderflow.config.TestConfig;
import com.orderflow.dto.OrderPayRequest;
import com.orderflow.dto.OrderPayResponse;
import com.orderflow.entity.Order;
import com.orderflow.entity.Payment;
import com.orderflow.enums.OrderStatus;
import com.orderflow.enums.PaymentStatus;
import com.orderflow.exception.BusinessException;
import com.orderflow.payment.PaymentAsyncService;
import com.orderflow.repository.OrderRepository;
import com.orderflow.repository.PaymentRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Import(TestConfig.class)
@DisplayName("支付处理服务测试")
class PaymentServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrderStatusService orderStatusService;

    @Mock
    private PaymentAsyncService paymentAsyncService;

    @InjectMocks
    private PaymentService paymentService;

    private Order testOrder;
    private Payment testPayment;
    private final String TEST_ORDER_ID = "order_test_001";
    private final String TEST_PAYMENT_ID = "payment_test_001";
    private final String TEST_TRANSACTION_ID = "txn_202605050001";

    @BeforeEach
    void setUp() {
        testOrder = OrderTestData.createTestOrder(TEST_ORDER_ID, OrderStatus.PENDING_PAYMENT);
        testPayment = createTestPayment(TEST_PAYMENT_ID, TEST_ORDER_ID, PaymentStatus.PENDING);
    }

    private Payment createTestPayment(String paymentId, String orderId, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setPaymentId(paymentId);
        payment.setOrderId(orderId);
        payment.setPaymentMethod("alipay");
        payment.setPaymentAmount(new BigDecimal("100.00"));
        payment.setStatus(status);
        payment.setCreatedAt(LocalDateTime.now());
        return payment;
    }

    private OrderPayRequest createTestPayRequest() {
        OrderPayRequest request = new OrderPayRequest();
        request.setOrderId(TEST_ORDER_ID);
        request.setPaymentMethod("alipay");
        return request;
    }

    @Test
    @DisplayName("测试支付请求校验 - 订单不存在")
    void testPaymentValidation_OrderNotFound() {
        OrderPayRequest request = createTestPayRequest();

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            paymentService.processPayment(request);
        });

        assertTrue(exception.getMessage().contains("订单不存在"));
    }

    @Test
    @DisplayName("测试支付请求校验 - 订单状态不支持支付")
    void testPaymentValidation_InvalidOrderStatus() {
        OrderPayRequest request = createTestPayRequest();
        testOrder.setStatus(OrderStatus.COMPLETED);

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            paymentService.processPayment(request);
        });

        assertTrue(exception.getMessage().contains("订单状态不支持支付"));
    }

    @Test
    @DisplayName("测试支付请求校验 - 不支持的支付方式")
    void testPaymentValidation_InvalidPaymentMethod() {
        OrderPayRequest request = createTestPayRequest();
        request.setPaymentMethod("invalid_method");

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            paymentService.processPayment(request);
        });

        assertTrue(exception.getMessage().contains("不支持的支付方式"));
    }

    @Test
    @DisplayName("测试支付创建")
    void testPaymentCreation() {
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            p.setPaymentId(TEST_PAYMENT_ID);
            return p;
        });
        when(orderStatusService.transitionStatus(anyString(), any(), anyString(), anyString()))
                .thenReturn(true);

        OrderPayRequest request = createTestPayRequest();
        paymentService.processPayment(request);

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, times(2)).save(paymentCaptor.capture());

        Payment savedPayment = paymentCaptor.getAllValues().get(0);
        assertEquals(TEST_ORDER_ID, savedPayment.getOrderId());
        assertEquals("alipay", savedPayment.getPaymentMethod());
        assertEquals(PaymentStatus.PENDING, savedPayment.getStatus());
        assertEquals(new BigDecimal("100.00"), savedPayment.getPaymentAmount());
    }

    @Test
    @DisplayName("测试支付成功处理")
    void testPaymentSuccessHandling() {
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            p.setPaymentId(TEST_PAYMENT_ID);
            return p;
        });
        when(orderStatusService.transitionStatus(anyString(), any(), anyString(), anyString()))
                .thenReturn(true);

        OrderPayRequest request = createTestPayRequest();
        OrderPayResponse response = paymentService.processPayment(request);

        assertNotNull(response);
        assertEquals(TEST_ORDER_ID, response.getOrderId());

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, times(2)).save(paymentCaptor.capture());

        Payment finalPayment = paymentCaptor.getAllValues().get(1);
        assertEquals(PaymentStatus.SUCCESS, finalPayment.getStatus());
        assertNotNull(finalPayment.getTransactionId());
        assertNotNull(finalPayment.getPaidAt());

        verify(orderStatusService, times(1)).transitionStatus(
                eq(TEST_ORDER_ID),
                eq(OrderStatus.PAID),
                eq("system"),
                anyString()
        );
    }

    @Test
    @DisplayName("测试异步支付发起")
    void testInitiateAsyncPayment() {
        OrderPayRequest request = createTestPayRequest();
        OrderPayResponse expectedResponse = OrderPayResponse.builder()
                .paymentId(TEST_PAYMENT_ID)
                .orderId(TEST_ORDER_ID)
                .status("pending")
                .paymentAmount(new BigDecimal("100.00"))
                .build();

        when(paymentAsyncService.initiatePayment(request)).thenReturn(expectedResponse);

        OrderPayResponse response = paymentService.initiateAsyncPayment(request);

        assertNotNull(response);
        assertEquals(TEST_PAYMENT_ID, response.getPaymentId());
        assertEquals("pending", response.getStatus());
        verify(paymentAsyncService, times(1)).initiatePayment(request);
    }

    @Test
    @DisplayName("测试支付回调处理 - 成功")
    void testHandlePaymentCallback_Success() {
        testPayment.setStatus(PaymentStatus.PENDING);
        testPayment.setTransactionId(TEST_TRANSACTION_ID);
        testOrder.setStatus(OrderStatus.PENDING_PAYMENT);

        when(paymentRepository.findByTransactionId(TEST_TRANSACTION_ID))
                .thenReturn(Optional.of(testPayment));
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(paymentRepository.save(any(Payment.class))).thenReturn(testPayment);
        when(orderStatusService.transitionStatus(anyString(), any(), anyString(), anyString()))
                .thenReturn(true);

        Payment result = paymentService.handlePaymentCallback(TEST_TRANSACTION_ID, true, null);

        assertNotNull(result);
        assertEquals(PaymentStatus.SUCCESS, result.getStatus());
        assertNotNull(result.getPaidAt());

        verify(orderStatusService, times(1)).transitionStatus(
                eq(TEST_ORDER_ID),
                eq(OrderStatus.PAID),
                eq("system"),
                anyString()
        );
    }

    @Test
    @DisplayName("测试支付回调处理 - 失败")
    void testHandlePaymentCallback_Failure() {
        testPayment.setStatus(PaymentStatus.PENDING);
        testPayment.setTransactionId(TEST_TRANSACTION_ID);
        String failReason = "余额不足";

        when(paymentRepository.findByTransactionId(TEST_TRANSACTION_ID))
                .thenReturn(Optional.of(testPayment));
        when(paymentRepository.save(any(Payment.class))).thenReturn(testPayment);

        Payment result = paymentService.handlePaymentCallback(TEST_TRANSACTION_ID, false, failReason);

        assertNotNull(result);
        assertEquals(PaymentStatus.FAILED, result.getStatus());
        assertEquals(failReason, result.getFailReason());

        verify(orderStatusService, never()).transitionStatus(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("测试支付回调处理 - 交易流水号不存在")
    void testHandlePaymentCallback_TransactionNotFound() {
        when(paymentRepository.findByTransactionId(TEST_TRANSACTION_ID))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            paymentService.handlePaymentCallback(TEST_TRANSACTION_ID, true, null);
        });

        assertTrue(exception.getMessage().contains("交易流水号不存在"));
    }

    @Test
    @DisplayName("测试支付回调处理 - 支付状态已处理")
    void testHandlePaymentCallback_AlreadyProcessed() {
        testPayment.setStatus(PaymentStatus.SUCCESS);
        testPayment.setTransactionId(TEST_TRANSACTION_ID);

        when(paymentRepository.findByTransactionId(TEST_TRANSACTION_ID))
                .thenReturn(Optional.of(testPayment));

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            paymentService.handlePaymentCallback(TEST_TRANSACTION_ID, true, null);
        });

        assertTrue(exception.getMessage().contains("支付状态已处理"));
    }

    @Test
    @DisplayName("测试获取支付记录")
    void testGetPayment() {
        when(paymentRepository.findById(TEST_PAYMENT_ID)).thenReturn(Optional.of(testPayment));

        Payment result = paymentService.getPayment(TEST_PAYMENT_ID);

        assertNotNull(result);
        assertEquals(TEST_PAYMENT_ID, result.getPaymentId());
    }

    @Test
    @DisplayName("测试获取支付记录 - 不存在")
    void testGetPayment_NotFound() {
        when(paymentRepository.findById(TEST_PAYMENT_ID)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            paymentService.getPayment(TEST_PAYMENT_ID);
        });

        assertTrue(exception.getMessage().contains("支付记录不存在"));
    }

    @Test
    @DisplayName("测试获取订单最后一笔支付")
    void testGetLastPaymentByOrderId() {
        Payment lastPayment = createTestPayment("payment_002", TEST_ORDER_ID, PaymentStatus.SUCCESS);
        when(paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(TEST_ORDER_ID))
                .thenReturn(Optional.of(lastPayment));

        Payment result = paymentService.getLastPaymentByOrderId(TEST_ORDER_ID);

        assertNotNull(result);
        assertEquals("payment_002", result.getPaymentId());
        assertEquals(PaymentStatus.SUCCESS, result.getStatus());
    }

    @Test
    @DisplayName("测试获取订单最后一笔支付 - 无支付记录")
    void testGetLastPaymentByOrderId_NoPayment() {
        when(paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(TEST_ORDER_ID))
                .thenReturn(Optional.empty());

        Payment result = paymentService.getLastPaymentByOrderId(TEST_ORDER_ID);

        assertNull(result);
    }
}
