package com.orderflow.entity;

import com.orderflow.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class OrderTestData {

    public static Order createTestOrder(String orderId, OrderStatus status) {
        Order order = new Order();
        order.setOrderId(orderId);
        order.setUserId("user_123");
        order.setOrderNo("ORD" + System.currentTimeMillis());
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setPaymentMethod("alipay");
        order.setCreatedAt(LocalDateTime.now());
        order.setVersion(0);
        return order;
    }

    public static Order createTestOrderWithItems(String orderId, OrderStatus status) {
        Order order = createTestOrder(orderId, status);

        List<OrderItem> items = new ArrayList<>();

        OrderItem item1 = new OrderItem();
        item1.setItemId("item_001");
        item1.setProductId("prod_001");
        item1.setQuantity(2);
        item1.setPrice(new BigDecimal("50.00"));
        item1.setOrder(order);
        items.add(item1);

        order.setItems(items);
        return order;
    }

    public static Payment createTestPayment(String paymentId, String orderId, String status) {
        Payment payment = new Payment();
        payment.setPaymentId(paymentId);
        payment.setOrderId(orderId);
        payment.setPaymentMethod("alipay");
        payment.setPaymentAmount(new BigDecimal("100.00"));
        payment.setStatus(com.orderflow.enums.PaymentStatus.valueOf(status.toUpperCase()));
        payment.setTransactionId("txn_" + System.currentTimeMillis());
        payment.setCreatedAt(LocalDateTime.now());
        return payment;
    }

    public static Refund createTestRefund(String refundId, String orderId, String status) {
        Refund refund = new Refund();
        refund.setRefundId(refundId);
        refund.setOrderId(orderId);
        refund.setRefundAmount(new BigDecimal("100.00"));
        refund.setRefundReason("商品质量问题");
        refund.setStatus(com.orderflow.enums.RefundStatus.valueOf(status.toUpperCase()));
        refund.setCreatedAt(LocalDateTime.now());
        return refund;
    }

    public static OrderStatusLog createTestStatusLog(String orderId, String fromStatus, String toStatus) {
        OrderStatusLog log = new OrderStatusLog();
        log.setStatusLogId("log_" + System.currentTimeMillis());
        log.setOrderId(orderId);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setOperator("system");
        log.setReason("测试状态变更");
        log.setChangedAt(LocalDateTime.now());
        return log;
    }
}
