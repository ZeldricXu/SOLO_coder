package com.orderflow.payment;

import com.orderflow.dto.OrderPayRequest;
import com.orderflow.dto.OrderPayResponse;
import com.orderflow.entity.Order;
import com.orderflow.entity.Payment;
import com.orderflow.enums.OrderStatus;
import com.orderflow.enums.PaymentMethod;
import com.orderflow.enums.PaymentStatus;
import com.orderflow.exception.BusinessException;
import com.orderflow.repository.OrderRepository;
import com.orderflow.repository.PaymentRepository;
import com.orderflow.service.OrderStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PaymentAsyncService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentAsyncService.class);

    private static final int PAYMENT_TIMEOUT_MINUTES = 30;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderStatusService orderStatusService;

    @Autowired
    private PaymentResultCache paymentResultCache;

    private final ConcurrentHashMap<String, CompletableFuture<PaymentResult>> pendingPayments = new ConcurrentHashMap<>();

    @Transactional(rollbackFor = Exception.class)
    public OrderPayResponse initiatePayment(OrderPayRequest request) {
        logger.info("发起异步支付请求，订单ID: {}", request.getOrderId());

        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> BusinessException.of("订单不存在: " + request.getOrderId()));

        validatePaymentRequest(order, request);

        Payment payment = createPayment(order, request);

        paymentResultCache.savePaymentStatus(payment.getPaymentId(), PaymentStatus.PENDING.name(), null);

        CompletableFuture<PaymentResult> future = new CompletableFuture<>();
        pendingPayments.put(payment.getPaymentId(), future);

        asyncProcessPayment(order, request, payment);

        logger.info("异步支付请求已发起，支付ID: {}, 订单ID: {}", payment.getPaymentId(), order.getOrderId());

        return OrderPayResponse.builder()
                .paymentId(payment.getPaymentId())
                .orderId(order.getOrderId())
                .status(PaymentStatus.PENDING.getCode())
                .paymentAmount(payment.getPaymentAmount())
                .build();
    }

    @Async("paymentExecutor")
    public void asyncProcessPayment(Order order, OrderPayRequest request, Payment payment) {
        logger.info("异步处理支付，支付ID: {}, 订单ID: {}", payment.getPaymentId(), order.getOrderId());

        try {
            PaymentChannelResult channelResult = callPaymentChannel(order, request, payment);

            if (channelResult.isSuccess()) {
                logger.info("支付渠道返回成功，支付ID: {}", payment.getPaymentId());
                completePayment(payment.getPaymentId(), channelResult.getTransactionId(), true, null);
            } else {
                logger.warn("支付渠道返回失败，支付ID: {}, 原因: {}", payment.getPaymentId(), channelResult.getFailReason());
                completePayment(payment.getPaymentId(), null, false, channelResult.getFailReason());
            }
        } catch (Exception e) {
            logger.error("异步支付处理异常，支付ID: {}", payment.getPaymentId(), e);
            completePayment(payment.getPaymentId(), null, false, "支付处理异常: " + e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void completePayment(String paymentId, String transactionId, boolean success, String failReason) {
        logger.info("完成支付处理，支付ID: {}, 成功: {}", paymentId, success);

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> BusinessException.of("支付记录不存在: " + paymentId));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            logger.warn("支付状态已处理，支付ID: {}, 当前状态: {}", paymentId, payment.getStatus());
            return;
        }

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> BusinessException.of("订单不存在: " + payment.getOrderId()));

        if (success) {
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setTransactionId(transactionId);
            payment.setPaidAt(LocalDateTime.now());
            paymentRepository.save(payment);

            if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
                orderStatusService.transitionStatus(
                        order.getOrderId(),
                        OrderStatus.PAID,
                        "system",
                        "支付成功，交易流水号: " + transactionId
                );
            }

            paymentResultCache.savePaymentStatus(paymentId, PaymentStatus.SUCCESS.name(), transactionId);
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailReason(failReason);
            paymentRepository.save(payment);

            paymentResultCache.savePaymentStatus(paymentId, PaymentStatus.FAILED.name(), null);
        }

        CompletableFuture<PaymentResult> future = pendingPayments.remove(paymentId);
        if (future != null) {
            future.complete(new PaymentResult(success, transactionId, failReason));
        }

        logger.info("支付处理完成，支付ID: {}, 状态: {}", paymentId, payment.getStatus().getCode());
    }

    public PaymentResult getPaymentResult(String paymentId) {
        PaymentStatusInfo cachedStatus = paymentResultCache.getPaymentStatus(paymentId);
        if (cachedStatus != null && !PaymentStatus.PENDING.name().equals(cachedStatus.getStatus())) {
            return new PaymentResult(
                    PaymentStatus.SUCCESS.name().equals(cachedStatus.getStatus()),
                    cachedStatus.getTransactionId(),
                    null
            );
        }

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> BusinessException.of("支付记录不存在: " + paymentId));

        return new PaymentResult(
                payment.getStatus() == PaymentStatus.SUCCESS,
                payment.getTransactionId(),
                payment.getFailReason()
        );
    }

    public CompletableFuture<PaymentResult> waitForPaymentResult(String paymentId, long timeoutMillis) {
        CompletableFuture<PaymentResult> future = pendingPayments.get(paymentId);
        if (future == null) {
            PaymentResult result = getPaymentResult(paymentId);
            return CompletableFuture.completedFuture(result);
        }
        return future;
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
        logger.info("调用支付渠道（异步），订单ID: {}, 支付方式: {}, 金额: {}",
                order.getOrderId(), request.getPaymentMethod(), order.getTotalAmount());

        try {
            Thread.sleep(200);
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

    private String generateTransactionId(String paymentMethod) {
        String prefix = "txn_";
        if ("alipay".equalsIgnoreCase(paymentMethod)) {
            prefix = "alipay_txn_";
        } else if ("wechat_pay".equalsIgnoreCase(paymentMethod)) {
            prefix = "wxpay_txn_";
        }
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static class PaymentResult {
        private final boolean success;
        private final String transactionId;
        private final String failReason;

        public PaymentResult(boolean success, String transactionId, String failReason) {
            this.success = success;
            this.transactionId = transactionId;
            this.failReason = failReason;
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
