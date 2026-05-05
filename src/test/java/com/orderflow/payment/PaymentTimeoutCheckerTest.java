package com.orderflow.payment;

import com.orderflow.entity.Order;
import com.orderflow.entity.Payment;
import com.orderflow.enums.OrderStatus;
import com.orderflow.enums.PaymentStatus;
import com.orderflow.repository.OrderRepository;
import com.orderflow.repository.PaymentRepository;
import com.orderflow.service.OrderStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("支付超时检查器测试")
class PaymentTimeoutCheckerTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusService orderStatusService;

    @Mock
    private PaymentResultCache paymentResultCache;

    @InjectMocks
    private PaymentTimeoutChecker timeoutChecker;

    private Payment pendingPayment;
    private Order pendingOrder;
    private final String TEST_ORDER_ID = "order_test_001";
    private final String TEST_PAYMENT_ID = "payment_test_001";

    @BeforeEach
    void setUp() {
        pendingOrder = new Order();
        pendingOrder.setOrderId(TEST_ORDER_ID);
        pendingOrder.setUserId("user_123");
        pendingOrder.setOrderNo("ORD202605050001");
        pendingOrder.setStatus(OrderStatus.PENDING_PAYMENT);
        pendingOrder.setTotalAmount(new BigDecimal("100.00"));
        pendingOrder.setPaymentMethod("alipay");

        pendingPayment = new Payment();
        pendingPayment.setPaymentId(TEST_PAYMENT_ID);
        pendingPayment.setOrderId(TEST_ORDER_ID);
        pendingPayment.setPaymentMethod("alipay");
        pendingPayment.setPaymentAmount(new BigDecimal("100.00"));
        pendingPayment.setStatus(PaymentStatus.PENDING);
        pendingPayment.setCreatedAt(LocalDateTime.now().minusMinutes(35));
    }

    @Test
    @DisplayName("测试超时支付处理 - 缓存中已有结果")
    void testProcessTimeoutPayment_CachedResultExists() {
        PaymentResultCache.PaymentStatusInfo cachedInfo = new PaymentResultCache.PaymentStatusInfo();
        cachedInfo.setStatus(PaymentStatus.SUCCESS.name());

        when(paymentResultCache.getPaymentStatus(TEST_PAYMENT_ID)).thenReturn(cachedInfo);

        timeoutChecker.processTimeoutPayment(pendingPayment);

        verify(paymentRepository, never()).save(any(Payment.class));
        verify(orderStatusService, never()).transitionStatusNoWait(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("测试超时支付处理 - 无缓存，支付渠道查询失败")
    void testProcessTimeoutPayment_NoCache_ChannelQueryFail() {
        when(paymentResultCache.getPaymentStatus(TEST_PAYMENT_ID)).thenReturn(null);
        when(paymentRepository.findByOrderIdAndStatus(TEST_ORDER_ID, PaymentStatus.SUCCESS))
                .thenReturn(Collections.emptyList());
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(pendingOrder));

        timeoutChecker.processTimeoutPayment(pendingPayment);

        verify(paymentRepository, times(1)).save(argThat(payment ->
                payment.getStatus() == PaymentStatus.FAILED &&
                "支付超时".equals(payment.getFailReason())
        ));

        verify(paymentResultCache, times(1)).savePaymentStatus(
                eq(TEST_PAYMENT_ID),
                eq(PaymentStatus.FAILED.name()),
                isNull()
        );
    }

    @Test
    @DisplayName("测试超时支付处理 - 订单状态不是待支付")
    void testProcessTimeoutPayment_OrderNotPending() {
        pendingOrder.setStatus(OrderStatus.PAID);

        when(paymentResultCache.getPaymentStatus(TEST_PAYMENT_ID)).thenReturn(null);
        when(paymentRepository.findByOrderIdAndStatus(TEST_ORDER_ID, PaymentStatus.SUCCESS))
                .thenReturn(Collections.emptyList());
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(pendingOrder));

        timeoutChecker.processTimeoutPayment(pendingPayment);

        verify(paymentRepository, times(1)).save(any(Payment.class));
        verify(orderStatusService, never()).transitionStatusNoWait(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("测试超时支付处理 - 已有其他成功支付")
    void testProcessTimeoutPayment_HasOtherSuccessfulPayment() {
        Payment successPayment = new Payment();
        successPayment.setPaymentId("payment_002");
        successPayment.setStatus(PaymentStatus.SUCCESS);

        when(paymentResultCache.getPaymentStatus(TEST_PAYMENT_ID)).thenReturn(null);
        when(paymentRepository.findByOrderIdAndStatus(TEST_ORDER_ID, PaymentStatus.SUCCESS))
                .thenReturn(Collections.singletonList(successPayment));

        timeoutChecker.processTimeoutPayment(pendingPayment);

        verify(paymentRepository, times(1)).save(any(Payment.class));
        verify(orderStatusService, never()).transitionStatusNoWait(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("测试超时支付处理 - 订单不存在")
    void testProcessTimeoutPayment_OrderNotFound() {
        when(paymentResultCache.getPaymentStatus(TEST_PAYMENT_ID)).thenReturn(null);
        when(paymentRepository.findByOrderIdAndStatus(TEST_ORDER_ID, PaymentStatus.SUCCESS))
                .thenReturn(Collections.emptyList());
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.empty());

        timeoutChecker.processTimeoutPayment(pendingPayment);

        verify(paymentRepository, times(1)).save(any(Payment.class));
        verify(orderStatusService, never()).transitionStatusNoWait(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("测试检查超时支付 - 空结果")
    void testCheckTimeoutPayments_Empty() {
        Page<Payment> emptyPage = new PageImpl<>(Collections.emptyList());

        when(paymentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(emptyPage);

        timeoutChecker.checkTimeoutPayments();

        verify(paymentRepository, times(1)).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("测试检查超时支付 - 有多页结果")
    void testCheckTimeoutPayments_MultiplePages() {
        List<Payment> page1Payments = new ArrayList<>();
        page1Payments.add(pendingPayment);

        Payment payment2 = new Payment();
        payment2.setPaymentId("payment_002");
        payment2.setOrderId("order_002");
        payment2.setStatus(PaymentStatus.PENDING);

        List<Payment> page2Payments = new ArrayList<>();
        page2Payments.add(payment2);

        Page<Payment> page1 = new PageImpl<>(page1Payments, null, 2);
        Page<Payment> page2 = new PageImpl<>(page2Payments, null, 2);

        when(paymentRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page1)
                .thenReturn(page2)
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        when(paymentResultCache.getPaymentStatus(anyString())).thenReturn(null);
        when(paymentRepository.findByOrderIdAndStatus(anyString(), eq(PaymentStatus.SUCCESS)))
                .thenReturn(Collections.emptyList());

        timeoutChecker.checkTimeoutPayments();

        verify(paymentRepository, times(3)).findAll(any(Specification.class), any(Pageable.class));
        verify(paymentRepository, times(2)).save(any(Payment.class));
    }
}
