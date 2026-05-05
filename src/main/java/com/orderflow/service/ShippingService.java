package com.orderflow.service;

import com.orderflow.dto.OrderShipRequest;
import com.orderflow.entity.Order;
import com.orderflow.entity.Shipping;
import com.orderflow.enums.OrderStatus;
import com.orderflow.enums.ShippingStatus;
import com.orderflow.exception.BusinessException;
import com.orderflow.repository.OrderRepository;
import com.orderflow.repository.ShippingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class ShippingService {

    private static final Logger logger = LoggerFactory.getLogger(ShippingService.class);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ShippingRepository shippingRepository;

    @Autowired
    private OrderStatusService orderStatusService;

    @Transactional(rollbackFor = Exception.class)
    public Shipping shipOrder(OrderShipRequest request) {
        logger.info("开始处理发货，订单ID: {}", request.getOrderId());

        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> BusinessException.of("订单不存在: " + request.getOrderId()));

        validateShippingRequest(order);

        Optional<Shipping> existingShipping = shippingRepository.findByOrderId(order.getOrderId());
        if (existingShipping.isPresent()) {
            throw BusinessException.of("该订单已发货");
        }

        Shipping shipping = createShipping(order, request);

        orderStatusService.transitionStatus(
                order.getOrderId(),
                OrderStatus.SHIPPED,
                "system",
                "订单已发货，承运商: " + request.getCarrier() + ", 运单号: " + request.getTrackingNo()
        );

        logger.info("发货处理完成，订单ID: {}, 运单号: {}", order.getOrderId(), request.getTrackingNo());

        return shipping;
    }

    private void validateShippingRequest(Order order) {
        if (order.getStatus() != OrderStatus.PAID) {
            throw BusinessException.of("订单状态不支持发货，当前状态: " + order.getStatus().getCode());
        }
    }

    private Shipping createShipping(Order order, OrderShipRequest request) {
        Shipping shipping = new Shipping();
        shipping.setOrderId(order.getOrderId());
        shipping.setCarrier(request.getCarrier());
        shipping.setTrackingNo(request.getTrackingNo());
        shipping.setShippedAt(LocalDateTime.now());
        shipping.setStatus(ShippingStatus.IN_TRANSIT);
        shippingRepository.save(shipping);
        return shipping;
    }

    public Shipping getShippingByOrderId(String orderId) {
        return shippingRepository.findByOrderId(orderId)
                .orElseThrow(() -> BusinessException.of("该订单暂无发货记录: " + orderId));
    }

    @Transactional(rollbackFor = Exception.class)
    public Shipping confirmDelivery(String orderId) {
        logger.info("确认收货，订单ID: {}", orderId);

        Shipping shipping = shippingRepository.findByOrderId(orderId)
                .orElseThrow(() -> BusinessException.of("发货记录不存在: " + orderId));

        if (shipping.getStatus() == ShippingStatus.DELIVERED) {
            throw BusinessException.of("该订单已确认收货");
        }

        shipping.setStatus(ShippingStatus.DELIVERED);
        shippingRepository.save(shipping);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> BusinessException.of("订单不存在: " + orderId));

        if (order.getStatus() == OrderStatus.SHIPPED) {
            orderStatusService.transitionStatus(
                    orderId,
                    OrderStatus.COMPLETED,
                    "user",
                    "用户确认收货"
            );
        }

        logger.info("确认收货完成，订单ID: {}", orderId);

        return shipping;
    }

    @Transactional(rollbackFor = Exception.class)
    public Shipping updateShippingStatus(String orderId, ShippingStatus status) {
        logger.info("更新物流状态，订单ID: {}, 状态: {}", orderId, status.getCode());

        Shipping shipping = shippingRepository.findByOrderId(orderId)
                .orElseThrow(() -> BusinessException.of("发货记录不存在: " + orderId));

        shipping.setStatus(status);
        shippingRepository.save(shipping);

        return shipping;
    }
}
