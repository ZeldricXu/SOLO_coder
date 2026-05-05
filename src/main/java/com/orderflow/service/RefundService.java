package com.orderflow.service;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
public class RefundService {

    private static final Logger logger = LoggerFactory.getLogger(RefundService.class);

    private static final List<OrderStatus> REFUND_ALLOWED_STATUSES = Arrays.asList(
            OrderStatus.PAID,
            OrderStatus.SHIPPED,
            OrderStatus.COMPLETED
    );

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderStatusService orderStatusService;

    @Transactional(rollbackFor = Exception.class)
    public Refund applyRefund(RefundApplyRequest request) {
        logger.info("申请退款，订单ID: {}", request.getOrderId());

        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> BusinessException.of("订单不存在: " + request.getOrderId()));

        validateRefundRequest(order, request);

        Refund refund = createRefund(order, request);

        if (order.getStatus() != OrderStatus.REFUNDING) {
            orderStatusService.transitionStatus(
                    order.getOrderId(),
                    OrderStatus.REFUNDING,
                    "user",
                    "用户申请退款，金额: " + request.getRefundAmount() + ", 原因: " + request.getRefundReason()
            );
        }

        logger.info("退款申请提交成功，退款ID: {}", refund.getRefundId());

        return refund;
    }

    private void validateRefundRequest(Order order, RefundApplyRequest request) {
        if (!REFUND_ALLOWED_STATUSES.contains(order.getStatus())) {
            throw BusinessException.of("订单状态不支持退款，当前状态: " + order.getStatus().getCode());
        }

        if (request.getRefundAmount() == null || request.getRefundAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.of("退款金额必须大于0");
        }

        if (request.getRefundAmount().compareTo(order.getTotalAmount()) > 0) {
            throw BusinessException.of("退款金额不能超过订单总金额");
        }

        if (request.getRefundReason() == null || request.getRefundReason().trim().isEmpty()) {
            throw BusinessException.of("退款原因不能为空");
        }

        List<Refund> existingRefunds = refundRepository.findByOrderId(order.getOrderId());
        for (Refund existing : existingRefunds) {
            if (existing.getStatus() == RefundStatus.PROCESSING) {
                throw BusinessException.of("该订单已有处理中的退款申请");
            }
        }
    }

    private Refund createRefund(Order order, RefundApplyRequest request) {
        Refund refund = new Refund();
        refund.setOrderId(order.getOrderId());
        refund.setRefundAmount(request.getRefundAmount());
        refund.setRefundReason(request.getRefundReason());
        refund.setStatus(RefundStatus.PROCESSING);
        refundRepository.save(refund);
        return refund;
    }

    @Transactional(rollbackFor = Exception.class)
    public Refund approveRefund(String refundId) {
        logger.info("审批通过退款，退款ID: {}", refundId);

        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> BusinessException.of("退款记录不存在: " + refundId));

        if (refund.getStatus() != RefundStatus.PROCESSING) {
            throw BusinessException.of("退款状态不允许审批，当前状态: " + refund.getStatus().getCode());
        }

        RefundChannelResult result = callRefundChannel(refund);

        if (result.isSuccess()) {
            handleRefundSuccess(refund, result);
            logger.info("退款处理成功，退款ID: {}", refundId);
        } else {
            handleRefundFailure(refund, result);
            logger.warn("退款处理失败，退款ID: {}, 原因: {}", refundId, result.getFailReason());
        }

        return refund;
    }

    private RefundChannelResult callRefundChannel(Refund refund) {
        logger.info("调用退款渠道，订单ID: {}, 退款金额: {}", refund.getOrderId(), refund.getRefundAmount());

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean success = Math.random() > 0.05;

        if (success) {
            return RefundChannelResult.success();
        } else {
            return RefundChannelResult.fail("退款渠道处理失败，请重试");
        }
    }

    private void handleRefundSuccess(Refund refund, RefundChannelResult result) {
        refund.setStatus(RefundStatus.SUCCESS);
        refund.setProcessedAt(LocalDateTime.now());
        refundRepository.save(refund);

        Order order = orderRepository.findById(refund.getOrderId())
                .orElseThrow(() -> BusinessException.of("订单不存在: " + refund.getOrderId()));

        orderStatusService.transitionStatus(
                order.getOrderId(),
                OrderStatus.REFUNDED,
                "system",
                "退款成功，退款金额: " + refund.getRefundAmount()
        );
    }

    private void handleRefundFailure(Refund refund, RefundChannelResult result) {
        refund.setStatus(RefundStatus.FAILED);
        refund.setFailReason(result.getFailReason());
        refund.setProcessedAt(LocalDateTime.now());
        refundRepository.save(refund);

        Order order = orderRepository.findById(refund.getOrderId())
                .orElseThrow(() -> BusinessException.of("订单不存在: " + refund.getOrderId()));

        Payment lastPayment = paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(order.getOrderId())
                .orElse(null);

        OrderStatus targetStatus;
        if (lastPayment != null && lastPayment.getStatus() == PaymentStatus.SUCCESS) {
            targetStatus = OrderStatus.PAID;
        } else {
            targetStatus = OrderStatus.PENDING_PAYMENT;
        }

        orderStatusService.transitionStatus(
                order.getOrderId(),
                targetStatus,
                "system",
                "退款失败，恢复订单状态，失败原因: " + result.getFailReason()
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public Refund rejectRefund(String refundId, String rejectReason) {
        logger.info("拒绝退款，退款ID: {}", refundId);

        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> BusinessException.of("退款记录不存在: " + refundId));

        if (refund.getStatus() != RefundStatus.PROCESSING) {
            throw BusinessException.of("退款状态不允许拒绝，当前状态: " + refund.getStatus().getCode());
        }

        refund.setStatus(RefundStatus.REJECTED);
        refund.setFailReason(rejectReason != null ? rejectReason : "退款申请被拒绝");
        refund.setProcessedAt(LocalDateTime.now());
        refundRepository.save(refund);

        Order order = orderRepository.findById(refund.getOrderId())
                .orElseThrow(() -> BusinessException.of("订单不存在: " + refund.getOrderId()));

        Payment lastPayment = paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(order.getOrderId())
                .orElse(null);

        OrderStatus targetStatus;
        if (lastPayment != null && lastPayment.getStatus() == PaymentStatus.SUCCESS) {
            if (order.getShippedAt() != null) {
                targetStatus = OrderStatus.SHIPPED;
            } else if (order.getCompletedAt() != null) {
                targetStatus = OrderStatus.COMPLETED;
            } else {
                targetStatus = OrderStatus.PAID;
            }
        } else {
            targetStatus = OrderStatus.PENDING_PAYMENT;
        }

        orderStatusService.transitionStatus(
                order.getOrderId(),
                targetStatus,
                "admin",
                "退款申请被拒绝，原因: " + (rejectReason != null ? rejectReason : "管理员拒绝")
        );

        logger.info("退款已拒绝，退款ID: {}", refundId);

        return refund;
    }

    public Refund getRefund(String refundId) {
        return refundRepository.findById(refundId)
                .orElseThrow(() -> BusinessException.of("退款记录不存在: " + refundId));
    }

    public List<Refund> getRefundsByOrderId(String orderId) {
        return refundRepository.findByOrderId(orderId);
    }

    public List<Refund> getProcessingRefunds() {
        return refundRepository.findByStatus(RefundStatus.PROCESSING);
    }

    private static class RefundChannelResult {
        private final boolean success;
        private final String failReason;

        private RefundChannelResult(boolean success, String failReason) {
            this.success = success;
            this.failReason = failReason;
        }

        public static RefundChannelResult success() {
            return new RefundChannelResult(true, null);
        }

        public static RefundChannelResult fail(String failReason) {
            return new RefundChannelResult(false, failReason);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getFailReason() {
            return failReason;
        }
    }
}
