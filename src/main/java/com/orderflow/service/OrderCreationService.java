package com.orderflow.service;

import com.orderflow.dto.OrderCreateRequest;
import com.orderflow.dto.OrderCreateResponse;
import com.orderflow.dto.OrderItemRequest;
import com.orderflow.entity.Order;
import com.orderflow.entity.OrderItem;
import com.orderflow.entity.OrderStatusLog;
import com.orderflow.enums.OrderStatus;
import com.orderflow.exception.BusinessException;
import com.orderflow.repository.OrderRepository;
import com.orderflow.repository.OrderStatusLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class OrderCreationService {

    private static final Logger logger = LoggerFactory.getLogger(OrderCreationService.class);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderStatusLogRepository orderStatusLogRepository;

    @Autowired
    private OrderStatusService orderStatusService;

    @Transactional(rollbackFor = Exception.class)
    public OrderCreateResponse createOrder(OrderCreateRequest request) {
        logger.info("开始创建订单，用户ID: {}", request.getUserId());

        validateCreateRequest(request);

        String orderNo = generateOrderNo();

        Order order = buildOrder(request, orderNo);

        List<OrderItem> orderItems = buildOrderItems(request.getItems(), order);
        order.setItems(orderItems);

        order.setStatus(OrderStatus.PENDING_PAYMENT);

        orderRepository.save(order);

        logStatusChange(order.getOrderId(), null, OrderStatus.PENDING_PAYMENT, "system", "订单创建成功");

        logger.info("订单创建成功，订单ID: {}, 订单号: {}", order.getOrderId(), orderNo);

        return OrderCreateResponse.builder()
                .orderId(order.getOrderId())
                .orderNo(order.getOrderNo())
                .status(order.getStatus().getCode())
                .build();
    }

    private void validateCreateRequest(OrderCreateRequest request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw BusinessException.of("订单项不能为空");
        }

        for (OrderItemRequest item : request.getItems()) {
            if (item.getProductId() == null || item.getProductId().trim().isEmpty()) {
                throw BusinessException.of("商品ID不能为空");
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw BusinessException.of("商品数量必须大于0");
            }
            if (item.getPrice() == null || item.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.of("商品价格必须大于0");
            }
        }
    }

    private Order buildOrder(OrderCreateRequest request, String orderNo) {
        Order order = new Order();
        order.setUserId(request.getUserId());
        order.setOrderNo(orderNo);
        order.setPaymentMethod(request.getPaymentMethod());

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (OrderItemRequest item : request.getItems()) {
            BigDecimal itemAmount = item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            totalAmount = totalAmount.add(itemAmount);
        }
        order.setTotalAmount(totalAmount);

        return order;
    }

    private List<OrderItem> buildOrderItems(List<OrderItemRequest> itemRequests, Order order) {
        List<OrderItem> orderItems = new ArrayList<>();
        for (OrderItemRequest itemRequest : itemRequests) {
            OrderItem orderItem = new OrderItem();
            orderItem.setProductId(itemRequest.getProductId());
            orderItem.setQuantity(itemRequest.getQuantity());
            orderItem.setPrice(itemRequest.getPrice());
            orderItem.setOrder(order);
            orderItems.add(orderItem);
        }
        return orderItems;
    }

    private String generateOrderNo() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        String timestamp = LocalDateTime.now().format(formatter);
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return timestamp + uuid;
    }

    private void logStatusChange(String orderId, OrderStatus fromStatus, OrderStatus toStatus,
                                 String operator, String reason) {
        OrderStatusLog log = new OrderStatusLog();
        log.setOrderId(orderId);
        log.setFromStatus(fromStatus != null ? fromStatus.getCode() : null);
        log.setToStatus(toStatus.getCode());
        log.setOperator(operator);
        log.setReason(reason);
        orderStatusLogRepository.save(log);
    }
}
