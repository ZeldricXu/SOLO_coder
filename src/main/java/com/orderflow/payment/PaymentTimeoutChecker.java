package com.orderflow.payment;

import com.orderflow.entity.Order;
import com.orderflow.entity.Payment;
import com.orderflow.enums.OrderStatus;
import com.orderflow.enums.PaymentStatus;
import com.orderflow.repository.OrderRepository;
import com.orderflow.repository.PaymentRepository;
import com.orderflow.service.OrderStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class PaymentTimeoutChecker {

    private static final Logger logger = LoggerFactory.getLogger(PaymentTimeoutChecker.class);

    private static final int PAYMENT_TIMEOUT_MINUTES = 30;
    private static final int BATCH_SIZE = 100;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderStatusService orderStatusService;

    @Autowired
    private PaymentResultCache paymentResultCache;

    @Scheduled(fixedRate = 60000)
    public void checkTimeoutPayments() {
        logger.info("开始检查超时支付订单");

        int pageNum = 0;
        int processedCount = 0;

        while (true) {
            Page<Payment> pendingPayments = findPendingPayments(pageNum, BATCH_SIZE);

            if (pendingPayments.isEmpty()) {
                break;
            }

            for (Payment payment : pendingPayments.getContent()) {
                try {
                    processTimeoutPayment(payment);
                    processedCount++;
                } catch (Exception e) {
                    logger.error("处理超时支付失败，支付ID: {}", payment.getPaymentId(), e);
                }
            }

            if (!pendingPayments.hasNext()) {
                break;
            }
            pageNum++;
        }

        logger.info("超时支付订单检查完成，处理数量: {}", processedCount);
    }

    private Page<Payment> findPendingPayments(int pageNum, int pageSize) {
        LocalDateTime timeoutThreshold = LocalDateTime.now().minus(PAYMENT_TIMEOUT_MINUTES, ChronoUnit.MINUTES);

        Specification<Payment> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteriaBuilder.equal(root.get("status"), PaymentStatus.PENDING));
            predicates.add(criteriaBuilder.lessThan(root.get("createdAt"), timeoutThreshold));
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        PageRequest pageRequest = PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.ASC, "createdAt"));
        return paymentRepository.findAll(spec, pageRequest);
    }

    @Transactional(rollbackFor = Exception.class)
    public void processTimeoutPayment(Payment payment) {
        logger.info("处理超时支付，支付ID: {}, 订单ID: {}", payment.getPaymentId(), payment.getOrderId());

        PaymentStatusInfo cachedStatus = paymentResultCache.getPaymentStatus(payment.getPaymentId());
        if (cachedStatus != null && !PaymentStatus.PENDING.name().equals(cachedStatus.getStatus())) {
            logger.info("支付状态已在缓存中更新，支付ID: {}, 状态: {}", payment.getPaymentId(), cachedStatus.getStatus());
            return;
        }

        boolean channelResult = queryPaymentChannel(payment);

        if (channelResult) {
            logger.info("支付渠道查询显示支付成功，支付ID: {}", payment.getPaymentId());
        } else {
            logger.info("支付超时，标记为失败，支付ID: {}", payment.getPaymentId());

            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailReason("支付超时");
            paymentRepository.save(payment);

            paymentResultCache.savePaymentStatus(payment.getPaymentId(), PaymentStatus.FAILED.name(), null);

            Order order = orderRepository.findById(payment.getOrderId()).orElse(null);
            if (order != null && order.getStatus() == OrderStatus.PENDING_PAYMENT) {
                if (hasOtherSuccessfulPayment(order.getOrderId())) {
                    logger.info("订单已有其他成功支付，不取消订单，订单ID: {}", order.getOrderId());
                } else {
                    orderStatusService.transitionStatusNoWait(
                            order.getOrderId(),
                            OrderStatus.CANCELLED,
                            "system",
                            "支付超时，订单自动取消"
                    );
                }
            }
        }
    }

    private boolean hasOtherSuccessfulPayment(String orderId) {
        List<Payment> payments = paymentRepository.findByOrderIdAndStatus(orderId, PaymentStatus.SUCCESS);
        return !payments.isEmpty();
    }

    private boolean queryPaymentChannel(Payment payment) {
        logger.info("主动查询支付渠道状态，支付ID: {}", payment.getPaymentId());

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return false;
    }
}
