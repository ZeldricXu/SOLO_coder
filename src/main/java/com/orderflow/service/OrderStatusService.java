package com.orderflow.service;

import com.orderflow.entity.Order;
import com.orderflow.entity.OrderStatusLog;
import com.orderflow.enums.OrderStatus;
import com.orderflow.exception.BusinessException;
import com.orderflow.lock.DistributedLockService;
import com.orderflow.repository.OrderRepository;
import com.orderflow.repository.OrderStatusLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class OrderStatusService {

    private static final Logger logger = LoggerFactory.getLogger(OrderStatusService.class);

    private static final Map<OrderStatus, List<OrderStatus>> STATUS_TRANSITION_RULES = new HashMap<>();

    private static final long LOCK_WAIT_TIME = 3000;
    private static final long LOCK_LEASE_TIME = 10000;

    static {
        STATUS_TRANSITION_RULES.put(OrderStatus.PENDING_PAYMENT,
                Arrays.asList(OrderStatus.PAID, OrderStatus.CANCELLED));
        STATUS_TRANSITION_RULES.put(OrderStatus.PAID,
                Arrays.asList(OrderStatus.SHIPPED, OrderStatus.REFUNDING));
        STATUS_TRANSITION_RULES.put(OrderStatus.SHIPPED,
                Arrays.asList(OrderStatus.COMPLETED, OrderStatus.REFUNDING));
        STATUS_TRANSITION_RULES.put(OrderStatus.REFUNDING,
                Arrays.asList(OrderStatus.REFUNDED, OrderStatus.PAID, OrderStatus.CANCELLED));
        STATUS_TRANSITION_RULES.put(OrderStatus.COMPLETED,
                Arrays.asList(OrderStatus.REFUNDING));
    }

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderStatusLogRepository orderStatusLogRepository;

    @Autowired
    private DistributedLockService distributedLockService;

    @Transactional(rollbackFor = Exception.class)
    public boolean transitionStatus(String orderId, OrderStatus targetStatus, String operator, String reason) {
        String lockKey = "order:status:" + orderId;
        logger.info("订单状态流转，订单ID: {}, 目标状态: {}, 尝试获取分布式锁", orderId, targetStatus.getCode());

        boolean locked = distributedLockService.tryLock(lockKey, LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.MILLISECONDS);
        if (!locked) {
            logger.error("获取分布式锁失败，订单状态变更被拒绝，订单ID: {}", orderId);
            throw BusinessException.of("订单状态变更繁忙，请稍后重试");
        }

        try {
            return doTransitionStatus(orderId, targetStatus, operator, reason);
        } finally {
            distributedLockService.unlock(lockKey);
            logger.debug("释放分布式锁，订单ID: {}", orderId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean transitionStatusNoWait(String orderId, OrderStatus targetStatus, String operator, String reason) {
        String lockKey = "order:status:" + orderId;
        logger.info("订单状态流转（无等待），订单ID: {}, 目标状态: {}", orderId, targetStatus.getCode());

        boolean locked = distributedLockService.tryLockNoWait(lockKey, LOCK_LEASE_TIME, TimeUnit.MILLISECONDS);
        if (!locked) {
            logger.warn("获取分布式锁失败（锁被占用），订单ID: {}", orderId);
            return false;
        }

        try {
            return doTransitionStatus(orderId, targetStatus, operator, reason);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    private boolean doTransitionStatus(String orderId, OrderStatus targetStatus, String operator, String reason) {
        logger.debug("执行订单状态流转，订单ID: {}, 目标状态: {}", orderId, targetStatus.getCode());

        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> BusinessException.of("订单不存在: " + orderId));

        OrderStatus currentStatus = order.getStatus();

        if (currentStatus == targetStatus) {
            logger.warn("订单状态未变化，订单ID: {}, 当前状态: {}", orderId, currentStatus.getCode());
            return false;
        }

        validateStatusTransition(currentStatus, targetStatus);

        order.setStatus(targetStatus);

        updateOrderTimestamp(order, targetStatus);

        orderRepository.save(order);

        logStatusChange(orderId, currentStatus, targetStatus, operator, reason);

        logger.info("订单状态流转成功，订单ID: {}, 从状态: {} 到状态: {}",
                orderId, currentStatus.getCode(), targetStatus.getCode());

        return true;
    }

    private void validateStatusTransition(OrderStatus currentStatus, OrderStatus targetStatus) {
        List<OrderStatus> allowedTransitions = STATUS_TRANSITION_RULES.get(currentStatus);

        if (allowedTransitions == null) {
            throw BusinessException.of("当前订单状态不允许变更: " + currentStatus.getCode());
        }

        if (!allowedTransitions.contains(targetStatus)) {
            throw BusinessException.of(
                    String.format("订单状态变更不合法，当前状态: %s，目标状态: %s",
                            currentStatus.getCode(), targetStatus.getCode())
            );
        }
    }

    private void updateOrderTimestamp(Order order, OrderStatus targetStatus) {
        LocalDateTime now = LocalDateTime.now();
        switch (targetStatus) {
            case PAID:
                order.setPaidAt(now);
                break;
            case SHIPPED:
                order.setShippedAt(now);
                break;
            case COMPLETED:
                order.setCompletedAt(now);
                break;
            default:
                break;
        }
    }

    private void logStatusChange(String orderId, OrderStatus fromStatus, OrderStatus toStatus,
                                 String operator, String reason) {
        OrderStatusLog log = new OrderStatusLog();
        log.setOrderId(orderId);
        log.setFromStatus(fromStatus != null ? fromStatus.getCode() : null);
        log.setToStatus(toStatus.getCode());
        log.setOperator(operator != null ? operator : "system");
        log.setReason(reason != null ? reason : "状态变更");
        orderStatusLogRepository.save(log);
    }

    public List<OrderStatusLog> getStatusHistory(String orderId) {
        return orderStatusLogRepository.findByOrderIdOrderByChangedAtDesc(orderId);
    }

    public boolean isValidTransition(OrderStatus currentStatus, OrderStatus targetStatus) {
        List<OrderStatus> allowedTransitions = STATUS_TRANSITION_RULES.get(currentStatus);
        return allowedTransitions != null && allowedTransitions.contains(targetStatus);
    }
}
