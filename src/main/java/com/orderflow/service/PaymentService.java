package com.orderflow.service;

import com.orderflow.dto.OrderPayRequest;
import com.orderflow.dto.OrderPayResponse;
import com.orderflow.entity.Order;
import com.orderflow.entity.Payment;
import com.orderflow.enums.OrderStatus;
import com.orderflow.enums.PaymentMethod;
import com.orderflow.enums.PaymentStatus;
import com.orderflow.exception.BusinessException;
import com.orderflow.payment.PaymentAsyncService;
import com.orderflow.repository.OrderRepository;
import com.orderflow.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderStatusService orderStatusService;

    @Autowired
    private PaymentAsyncService paymentAsyncService;

    @Transactional(rollbackFor = Exception.class)
    public OrderPayResponse processPayment(OrderPayRequest request) {
        logger.info("开始处理同步支付，订单ID: {}", request.getOrderId());

        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> BusinessException.of("订单不存在: " + request.getOrderId()));

        validatePaymentRequest(order, request);

        Payment payment = createPayment(order, request);

        PaymentChannelResult channelResult = callPaymentChannel(order, request, payment);

        if (channelResult.isSuccess()) {
            handlePaymentSuccess(order, payment, channelResult);
            logger.info("支付成功，订单ID: {}, 支付ID: {}", order.getOrderId(), payment.getPaymentId());
        } else {
            handlePaymentFailure(payment, channelResult);
            logger.warn("支付失败，订单ID: {}, 失败原因: {}", order.getOrderId(), channelResult.getFailReason());
        }

        return OrderPayResponse.builder()
                .paymentId(payment.getPaymentId())
                .orderId(order.getOrderId())
                .status(payment.getStatus().getCode())
                .paymentAmount(payment.getPaymentAmount())
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderPayResponse initiateAsyncPayment(OrderPayRequest request) {
        logger.info("发起异步支付，订单ID: {}", request.getOrderId());
        return paymentAsyncService.initiatePayment(request);
    }

    private void validatePaymentRequest(Order order, OrderPayRequest request) {
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw BusinessException.of("订单状态不支持支付，当前状态: " + order.getStatus().getCode());
        }

        PaymentMethod paymentMethod = PaymentMethod.getByCode(request.getPaymentMethod());
        if (paymentMethod == null) {
            throw BusinessException.of("不支持的支付方式: " + request.getPaymentMethod());
        }
    }

    private Payment createPayment(Order order, OrderPayRequest request) {
        Payment payment = new Payment();
        payment.setOrderId(order.getOrderId());
        payment.setPaymentMethod(request.getPaymentMethod());
        payment.setPaymentAmount(order.getTotalAmount());
        payment.setStatus(PaymentStatus.PENDING);
        paymentRepository.save(payment);
        return payment;
    }

    private PaymentChannelResult callPaymentChannel(Order order, OrderPayRequest request, Payment payment) {
        logger.info("调用支付渠道，订单ID: {}, 支付方式: {}, 金额: {}",
                order.getOrderId(), request.getPaymentMethod(), order.getTotalAmount());

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean success = Math.random() > 0.1;

        if (success) {
            String transactionId = generateTransactionId(request.getPaymentMethod());
            return PaymentChannelResult.success(transactionId);
        } else {
            return PaymentChannelResult.fail("支付渠道处理失败，请重试");
        }
    }

    private void handlePaymentSuccess(Order order, Payment payment, PaymentChannelResult channelResult) {
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setTransactionId(channelResult.getTransactionId());
        payment.setPaidAt(LocalDateTime.now());
        paymentRepository.save(payment);

        orderStatusService.transitionStatus(
                order.getOrderId(),
                OrderStatus.PAID,
                "system",
                "支付成功，交易流水号: " + channelResult.getTransactionId()
        );
    }

    private void handlePaymentFailure(Payment payment, PaymentChannelResult channelResult) {
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailReason(channelResult.getFailReason());
        paymentRepository.save(payment);
    }

    private String generateTransactionId(String paymentMethod) {
        String prefix = "txn_";
        if ("alipay".equalsIgnoreCase(paymentMethod)) {
            prefix = "alipay_txn_";
        } else if ("wechat_pay".equalsIgnoreCase(paymentMethod)) {
            prefix = "wxpay_txn_";
        }
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public Payment getPayment(String paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> BusinessException.of("支付记录不存在: " + paymentId));
    }

    public Payment getLastPaymentByOrderId(String orderId) {
        return paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(orderId)
                .orElse(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public Payment handlePaymentCallback(String transactionId, boolean success, String failReason) {
        Payment payment = paymentRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> BusinessException.of("交易流水号不存在: " + transactionId));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw BusinessException.of("支付状态已处理，当前状态: " + payment.getStatus().getCode());
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> BusinessException.of("订单不存在: " + payment.getOrderId()));

        if (success) {
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setPaidAt(LocalDateTime.now());
            paymentRepository.save(payment);

            if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
                orderStatusService.transitionStatus(
                        order.getOrderId(),
                        OrderStatus.PAID,
                        "system",
                        "支付回调成功，交易流水号: " + transactionId
                );
            }
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailReason(failReason);
            paymentRepository.save(payment);
        }

        return payment;
    }

    private static class PaymentChannelResult {
        private final boolean success;
        private final String transactionId;
        private final String failReason;

        private PaymentChannelResult(boolean success, String transactionId, String failReason) {
            this.success = success;
            this.transactionId = transactionId;
            this.failReason = failReason;
        }

        public static PaymentChannelResult success(String transactionId) {
            return new PaymentChannelResult(true, transactionId, null);
        }

        public static PaymentChannelResult fail(String failReason) {
            return new PaymentChannelResult(false, null, failReason);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getTransactionId() {
            return transactionId;
        }

        public String getFailReason() {
            return failReason;
        }
    }
}
