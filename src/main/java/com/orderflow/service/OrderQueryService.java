package com.orderflow.service;

import com.orderflow.common.PageResult;
import com.orderflow.dto.OrderQueryRequest;
import com.orderflow.entity.Order;
import com.orderflow.entity.OrderItem;
import com.orderflow.entity.OrderStatusLog;
import com.orderflow.entity.Payment;
import com.orderflow.enums.OrderStatus;
import com.orderflow.exception.BusinessException;
import com.orderflow.repository.OrderItemRepository;
import com.orderflow.repository.OrderRepository;
import com.orderflow.repository.OrderStatusLogRepository;
import com.orderflow.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import javax.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderQueryService {

    private static final Logger logger = LoggerFactory.getLogger(OrderQueryService.class);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private OrderStatusLogRepository orderStatusLogRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    public Order getOrderDetail(String orderId) {
        logger.info("查询订单详情，订单ID: {}", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> BusinessException.of("订单不存在: " + orderId));

        return order;
    }

    public List<OrderItem> getOrderItems(String orderId) {
        logger.info("查询订单项，订单ID: {}", orderId);
        return orderItemRepository.findByOrder_OrderId(orderId);
    }

    public List<OrderStatusLog> getOrderStatusHistory(String orderId) {
        logger.info("查询订单状态历史，订单ID: {}", orderId);
        return orderStatusLogRepository.findByOrderIdOrderByChangedAtDesc(orderId);
    }

    public List<Payment> getOrderPayments(String orderId) {
        logger.info("查询订单支付记录，订单ID: {}", orderId);
        return paymentRepository.findByOrderId(orderId);
    }

    public List<Order> getOrdersByUserId(String userId) {
        logger.info("查询用户订单列表，用户ID: {}", userId);
        return orderRepository.findByUserId(userId);
    }

    public List<Order> getOrdersByUserIdAndStatus(String userId, String statusCode) {
        logger.info("查询用户订单列表，用户ID: {}, 状态: {}", userId, statusCode);

        OrderStatus status = OrderStatus.getByCode(statusCode);
        if (status == null) {
            throw BusinessException.of("无效的订单状态: " + statusCode);
        }

        return orderRepository.findByUserIdAndStatus(userId, status);
    }

    public PageResult<Order> queryOrders(OrderQueryRequest request) {
        logger.info("分页查询订单列表，用户ID: {}, 状态: {}, 页码: {}, 页大小: {}",
                request.getUserId(), request.getStatus(), request.getPageNum(), request.getPageSize());

        int pageNum = Math.max(1, request.getPageNum() != null ? request.getPageNum() : 1);
        int pageSize = Math.max(1, Math.min(100, request.getPageSize() != null ? request.getPageSize() : 10));

        Pageable pageable = PageRequest.of(pageNum - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<Order> specification = buildSpecification(request);

        Page<Order> page = orderRepository.findAll(specification, pageable);

        return new PageResult<>(
                page.getContent(),
                page.getTotalElements(),
                pageNum,
                pageSize
        );
    }

    private Specification<Order> buildSpecification(OrderQueryRequest request) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (request.getUserId() != null && !request.getUserId().trim().isEmpty()) {
                predicates.add(criteriaBuilder.equal(root.get("userId"), request.getUserId()));
            }

            if (request.getStatus() != null && !request.getStatus().trim().isEmpty()) {
                OrderStatus status = OrderStatus.getByCode(request.getStatus());
                if (status != null) {
                    predicates.add(criteriaBuilder.equal(root.get("status"), status));
                }
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    public Order getOrderByOrderNo(String orderNo) {
        logger.info("根据订单号查询订单，订单号: {}", orderNo);
        return orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> BusinessException.of("订单不存在: " + orderNo));
    }
}
